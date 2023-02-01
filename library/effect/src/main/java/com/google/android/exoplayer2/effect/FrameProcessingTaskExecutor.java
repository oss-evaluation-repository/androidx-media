/*
 * Copyright 2022 The Android Open Source Project
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
package com.google.android.exoplayer2.effect;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.util.FrameProcessingException;
import com.google.android.exoplayer2.util.FrameProcessor;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * Wrapper around a single thread {@link ExecutorService} for executing {@link FrameProcessingTask}
 * instances.
 *
 * <p>Public methods can be called from any thread.
 *
 * <p>The wrapper handles calling {@link
 * FrameProcessor.Listener#onFrameProcessingError(FrameProcessingException)} for errors that occur
 * during these tasks. The listener is invoked from the {@link ExecutorService}. Errors are assumed
 * to be non-recoverable, so the {@code FrameProcessingTaskExecutor} should be released if an error
 * occurs.
 *
 * <p>{@linkplain #submitWithHighPriority(FrameProcessingTask) High priority tasks} are always
 * executed before {@linkplain #submit(FrameProcessingTask) default priority tasks}. Tasks with
 * equal priority are executed in FIFO order.
 */
/* package */ final class FrameProcessingTaskExecutor {

  private final ExecutorService singleThreadExecutorService;
  private final FrameProcessor.Listener listener;
  private final Object lock;

  @GuardedBy("lock")
  private final ArrayDeque<FrameProcessingTask> highPriorityTasks;

  @GuardedBy("lock")
  private boolean shouldCancelTasks;

  /** Creates a new instance. */
  public FrameProcessingTaskExecutor(
      ExecutorService singleThreadExecutorService, FrameProcessor.Listener listener) {
    this.singleThreadExecutorService = singleThreadExecutorService;
    this.listener = listener;
    lock = new Object();
    highPriorityTasks = new ArrayDeque<>();
  }

  /**
   * Submits the given {@link FrameProcessingTask} to be executed after all pending tasks have
   * completed.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void submit(FrameProcessingTask task) {
    @Nullable RejectedExecutionException executionException = null;
    synchronized (lock) {
      if (shouldCancelTasks) {
        return;
      }
      try {
        wrapTaskAndSubmitToExecutorService(task, /* isFlushOrReleaseTask= */ false);
      } catch (RejectedExecutionException e) {
        executionException = e;
      }
    }

    if (executionException != null) {
      handleException(executionException);
    }
  }

  /**
   * Submits the given {@link FrameProcessingTask} to be executed after the currently running task
   * and all previously submitted high-priority tasks have completed.
   *
   * <p>Tasks that were previously {@linkplain #submit(FrameProcessingTask) submitted} without
   * high-priority and have not started executing will be executed after this task is complete.
   */
  public void submitWithHighPriority(FrameProcessingTask task) {
    synchronized (lock) {
      if (shouldCancelTasks) {
        return;
      }
      highPriorityTasks.add(task);
    }
    // If the ExecutorService has non-started tasks, the first of these non-started tasks will run
    // the task passed to this method. Just in case there are no non-started tasks, submit another
    // task to run high-priority tasks.
    submit(() -> {});
  }

  /**
   * Flushes all scheduled tasks.
   *
   * <p>During flush, the {@code FrameProcessingTaskExecutor} ignores the {@linkplain #submit
   * submission of new tasks}. The tasks that are submitted before flushing are either executed or
   * canceled when this method returns.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void flush() throws InterruptedException {
    synchronized (lock) {
      shouldCancelTasks = true;
      highPriorityTasks.clear();
    }

    CountDownLatch latch = new CountDownLatch(1);
    wrapTaskAndSubmitToExecutorService(
        () -> {
          synchronized (lock) {
            shouldCancelTasks = false;
          }
          latch.countDown();
        },
        /* isFlushOrReleaseTask= */ true);
    latch.await();
  }

  /**
   * Cancels remaining tasks, runs the given release task, and shuts down the background thread.
   *
   * @param releaseTask A {@link FrameProcessingTask} to execute before shutting down the background
   *     thread.
   * @param releaseWaitTimeMs How long to wait for the release task to terminate, in milliseconds.
   * @throws InterruptedException If interrupted while releasing resources.
   */
  public void release(FrameProcessingTask releaseTask, long releaseWaitTimeMs)
      throws InterruptedException {
    synchronized (lock) {
      shouldCancelTasks = true;
      highPriorityTasks.clear();
    }
    Future<?> releaseFuture =
        wrapTaskAndSubmitToExecutorService(releaseTask, /* isFlushOrReleaseTask= */ true);
    singleThreadExecutorService.shutdown();
    try {
      if (!singleThreadExecutorService.awaitTermination(releaseWaitTimeMs, MILLISECONDS)) {
        listener.onFrameProcessingError(new FrameProcessingException("Release timed out"));
      }
      releaseFuture.get();
    } catch (ExecutionException e) {
      listener.onFrameProcessingError(new FrameProcessingException(e));
    }
  }

  private Future<?> wrapTaskAndSubmitToExecutorService(
      FrameProcessingTask defaultPriorityTask, boolean isFlushOrReleaseTask) {
    return singleThreadExecutorService.submit(
        () -> {
          try {
            synchronized (lock) {
              if (shouldCancelTasks && !isFlushOrReleaseTask) {
                return;
              }
            }

            @Nullable FrameProcessingTask nextHighPriorityTask;
            while (true) {
              synchronized (lock) {
                // Lock only polling to prevent blocking the public method calls.
                nextHighPriorityTask = highPriorityTasks.poll();
              }
              if (nextHighPriorityTask == null) {
                break;
              }
              nextHighPriorityTask.run();
            }
            defaultPriorityTask.run();
          } catch (Exception e) {
            handleException(e);
          }
        });
  }

  private void handleException(Exception exception) {
    synchronized (lock) {
      if (shouldCancelTasks) {
        // Ignore exception after cancelation as it can be caused by a previously reported exception
        // that is the reason for the cancelation.
        return;
      }
      shouldCancelTasks = true;
    }
    listener.onFrameProcessingError(FrameProcessingException.from(exception));
  }
}
