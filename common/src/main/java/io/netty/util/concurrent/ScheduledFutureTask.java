/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.util.concurrent;

import io.netty.util.internal.CallableEventExecutorAdapter;
import io.netty.util.internal.OneTimeTask;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@SuppressWarnings("ComparableImplementedButEqualsNotOverridden")
final class ScheduledFutureTask<V> extends PromiseTask<V> implements ScheduledFuture<V> {
    private static final AtomicLong nextTaskId = new AtomicLong();
    private static final long START_TIME = System.nanoTime();

    static long nanoTime() {
        return System.nanoTime() - START_TIME;
    }

    static long deadlineNanos(long delay) {
        return nanoTime() + delay;
    }

    private final long id = nextTaskId.getAndIncrement();
    private Queue<ScheduledFutureTask<?>> delayedTaskQueue;
    private long deadlineNanos;
    /* 0 - no repeat, >0 - repeat at fixed rate, <0 - repeat with fixed delay */
    private final long periodNanos;

    ScheduledFutureTask(EventExecutor executor, Queue<ScheduledFutureTask<?>> delayedTaskQueue,
                        Callable<V> callable, long nanoTime, long period) {
        super(executor.unwrap(), callable);
        if (period == 0) {
            throw new IllegalArgumentException("period: 0 (expected: != 0)");
        }

        this.delayedTaskQueue = delayedTaskQueue;
        deadlineNanos = nanoTime;
        periodNanos = period;
    }

    ScheduledFutureTask(EventExecutor executor, Queue<ScheduledFutureTask<?>> delayedTaskQueue,
                        Callable<V> callable, long nanoTime) {
        super(executor.unwrap(), callable);
        this.delayedTaskQueue = delayedTaskQueue;
        deadlineNanos = nanoTime;
        periodNanos = 0;
    }

    @Override
    protected EventExecutor executor() {
        return executor;
    }

    public long deadlineNanos() {
        return deadlineNanos;
    }

    public long delayNanos() {
        return Math.max(0, deadlineNanos() - nanoTime());
    }

    public long delayNanos(long currentTimeNanos) {
        return Math.max(0, deadlineNanos() - (currentTimeNanos - START_TIME));
    }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(delayNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    public int compareTo(Delayed o) {
        if (this == o) {
            return 0;
        }

        ScheduledFutureTask<?> that = (ScheduledFutureTask<?>) o;
        long d = deadlineNanos() - that.deadlineNanos();
        if (d < 0) {
            return -1;
        } else if (d > 0) {
            return 1;
        } else if (id < that.id) {
            return -1;
        } else if (id == that.id) {
            throw new Error();
        } else {
            return 1;
        }
    }

    @Override
    public void run() {
        assert executor().inEventLoop();
        assert !delayedTaskQueue.contains(task);

        try {
            if (isMigrationPending()) {
                scheduleWithNewExecutor();
            } else if (needsLaterExecution()) {
                // Treat one time and periodic tasks in the same way: Try again after 10ms.
                rescheduleTask(TimeUnit.MICROSECONDS.toNanos(10));
            } else {
                // delayed tasks executed once
                if (periodNanos == 0) {
                    if (setUncancellableInternal()) {
                        V result = task.call();
                        setSuccessInternal(result);
                    }
                // periodically executed tasks
                } else {
                    // check if is done as it may was cancelled
                    if (!isCancelled()) {
                        task.call();
                        rescheduleTask(periodNanos);
                    }
                }
            }
        } catch (Throwable cause) {
            setFailureInternal(cause);
        }
    }

    @Override
    protected StringBuilder toStringBuilder() {
        StringBuilder buf = super.toStringBuilder();
        buf.setCharAt(buf.length() - 1, ',');
        buf.append(" id: ");
        buf.append(id);
        buf.append(", deadline: ");
        buf.append(deadlineNanos);
        buf.append(", period: ");
        buf.append(periodNanos);
        buf.append(')');
        return buf;
    }

    private void rescheduleTask(long p) {
        if (!executor().isShutdown()) {
            if (p > 0) {
                deadlineNanos += p;
            } else {
                deadlineNanos = nanoTime() - p;
            }
            if (!isCancelled()) {
                delayedTaskQueue.add(this);
            }
        }
    }

    private boolean needsLaterExecution() {
        return task instanceof CallableEventExecutorAdapter &&
                ((CallableEventExecutorAdapter) task).executor() instanceof PausableEventExecutor &&
                !((PausableEventExecutor) ((CallableEventExecutorAdapter) task).executor()).isAcceptingNewTasks();
    }

    private boolean isMigrationPending() {
        return !isCancelled() &&
                task instanceof CallableEventExecutorAdapter &&
                executor() != ((CallableEventExecutorAdapter) task).executor().unwrap();
    }

    private void scheduleWithNewExecutor() {
        EventExecutor newExecutor = ((CallableEventExecutorAdapter) task).executor().unwrap();

        if (newExecutor instanceof SingleThreadEventExecutor) {
            if (!newExecutor.isShutdown()) {
                executor = newExecutor;
                delayedTaskQueue = ((SingleThreadEventExecutor) newExecutor).delayedTaskQueue;

                executor.execute(new OneTimeTask() {
                    @Override
                    public void run() {
                        // Treat one time and periodic task the same: Try again as soon as possible.
                        rescheduleTask(0);
                    }
                });
            }
        } else {
            throw new UnsupportedOperationException("task migration unsupported");
        }
    }
}
