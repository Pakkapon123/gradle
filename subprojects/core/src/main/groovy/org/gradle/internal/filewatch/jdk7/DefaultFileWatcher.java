/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.filewatch.jdk7;

import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.filewatch.FileWatchInputs;
import org.gradle.internal.filewatch.FileWatcher;
import org.gradle.internal.filewatch.FileWatcherListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class DefaultFileWatcher implements FileWatcher {
    private final FileWatcherStopper stopper;
    private final FileWatcherTask fileWatcherTask;

    public DefaultFileWatcher(ExecutorService executor, WatchStrategy watchStrategy, FileWatcherListener listener) throws IOException {
        AtomicBoolean runningFlag = new AtomicBoolean(true);
        this.fileWatcherTask = new FileWatcherTask(watchStrategy, runningFlag, listener);
        Future<?> taskCompletion = executor.submit(fileWatcherTask);
        this.stopper = new FileWatcherStopper(runningFlag, taskCompletion);
    }

    @Override
    public void watch(FileWatchInputs inputs) throws IOException {
        fileWatcherTask.watch(inputs);
    }

    @Override
    public void stop() {
        stopper.stop();
    }

    static class FileWatcherTask implements Runnable {
        private static final Logger LOGGER = LoggerFactory.getLogger(FileWatcherTask.class);
        static final int POLL_TIMEOUT_MILLIS = 250;
        private final AtomicBoolean runningFlag;
        private final WatchStrategy watchStrategy;
        private final DirTreeWatchRegistry dirTreeWatchRegistry;
        private final IndividualFileWatchRegistry individualFileWatchRegistry;
        private final FileWatcherListener listener;

        public FileWatcherTask(WatchStrategy watchStrategy, AtomicBoolean runningFlag, FileWatcherListener listener) throws IOException {
            this.listener = listener;
            this.runningFlag = runningFlag;
            this.watchStrategy = watchStrategy;
            this.dirTreeWatchRegistry = DirTreeWatchRegistry.create(watchStrategy);
            this.individualFileWatchRegistry = new IndividualFileWatchRegistry(watchStrategy);
        }

        void watch(FileWatchInputs inputs) throws IOException {
            dirTreeWatchRegistry.register(inputs.getDirectoryTrees());
            individualFileWatchRegistry.register(inputs.getFiles());
        }

        public void run() {
            try {
                try {
                    watchLoop();
                } catch (InterruptedException e) {
                    // ignore
                }
            } finally {
                watchStrategy.close();
            }
        }

        protected void watchLoop() throws InterruptedException {
            while (watchLoopRunning()) {
                watchStrategy.pollChanges(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS, new WatchHandler() {
                    @Override
                    public void onOverflow() {
                        listener.onOverflow();
                    }

                    @Override
                    public void onChange(ChangeDetails changeDetails) {
                        dirTreeWatchRegistry.handleChange(changeDetails, listener);
                        individualFileWatchRegistry.handleChange(changeDetails, listener);
                    }
                });
            }
        }

        protected boolean watchLoopRunning() {
            return runningFlag.get() && !Thread.currentThread().isInterrupted();
        }
    }

    static class FileWatcherStopper implements Stoppable {
        private final AtomicBoolean runningFlag;
        private final Future<?> taskCompletion;

        private static final int STOP_TIMEOUT_SECONDS = 10;

        public FileWatcherStopper(AtomicBoolean runningFlag, Future<?> taskCompletion) {
            this.runningFlag = runningFlag;
            this.taskCompletion = taskCompletion;
        }

        @Override
        public synchronized void stop() {
            if (runningFlag.compareAndSet(true, false)) {
                waitForStop();
            }
        }

        private void waitForStop() {
            try {
                taskCompletion.get(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // ignore
            } catch (ExecutionException e) {
                // ignore
            } catch (TimeoutException e) {
                throw new RuntimeException("Running FileWatcherService wasn't stopped in timeout limits.", e);
            }
        }
    }
}
