/*
 * Copyright 2012 The Netty Project
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

import io.netty.util.metrics.EventExecutorMetrics;
import io.netty.util.metrics.NoEventExecutorMetrics;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Default implementation of {@link MultithreadEventExecutorGroup} which will use {@link DefaultEventExecutor}
 * instances to handle the tasks.
 */
public class DefaultEventExecutorGroup
        extends MultithreadEventExecutorGroup<EventExecutor, EventExecutorMetrics<EventExecutor>> {

    /**
     * Create a new instance.
     *
     * @param nEventExecutors   the number of {@link DefaultEventExecutor}s that this group will use.
     */
    public DefaultEventExecutorGroup(int nEventExecutors) {
        this(nEventExecutors, (Executor) null);
    }

    /**
     * Create a new instance.
     *
     * @param nEventExecutors   the number of {@link DefaultEventExecutor}s that this group will use.
     * @param executor  the {@link Executor} responsible for executing the work handled by
     *                  this {@link EventExecutorGroup}.
     */
    public DefaultEventExecutorGroup(int nEventExecutors, Executor executor) {
        super(nEventExecutors, executor, null, null);
    }

    /**
     * Create a new instance.
     *
     * @param nEventExecutors   the number of {@link DefaultEventExecutor}s that this group will use.
     * @param executorFactory   the {@link ExecutorFactory} which produces the {@link Executor} responsible for
     *                          executing the work handled by this {@link EventExecutorGroup}.
     */
    public DefaultEventExecutorGroup(int nEventExecutors, ExecutorFactory executorFactory) {
        super(nEventExecutors, executorFactory, null, null);
    }

    @Override
    protected EventExecutor newChild(Executor executor, EventExecutorMetrics metrics, Object... args) throws Exception {
        return new DefaultEventExecutor(this, executor);
    }

    protected EventExecutorScheduler<EventExecutor, EventExecutorMetrics<EventExecutor>>
    newDefaultScheduler(int nEventExecutors) {
        return new RoundRobinEventExecutorScheduler();
    }

    private static final class RoundRobinEventExecutorScheduler
            extends AbstractEventExecutorScheduler<EventExecutor, EventExecutorMetrics<EventExecutor>> {

        private final AtomicInteger index = new AtomicInteger();

        @Override
        public EventExecutor next() {
            return children.get(index.getAndIncrement() % children.size());
        }

        @Override
        public EventExecutorMetrics<EventExecutor> newMetrics() {
            return NoEventExecutorMetrics.INSTANCE;
        }
    }
}
