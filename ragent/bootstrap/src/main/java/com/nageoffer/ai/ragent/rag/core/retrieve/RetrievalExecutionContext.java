/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.infra.chat.CancellableChatCall;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One request's monotonic deadline and cancellation tree.
 * Child contexts share the same absolute deadline; cancelling a child only
 * stops that channel while cancelling a parent stops every unfinished child.
 */
public final class RetrievalExecutionContext {

    public enum State { ACTIVE, TIMED_OUT, CANCELLED }

    private final long deadlineNanos;
    private final long budgetMillis;
    private final RetrievalExecutionContext parent;
    private final Set<RetrievalExecutionContext> children = new CopyOnWriteArraySet<>();
    private final Set<Future<?>> futures = new CopyOnWriteArraySet<>();
    private final Set<CancellableChatCall> calls = new CopyOnWriteArraySet<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);

    private RetrievalExecutionContext(long deadlineNanos,
                                      long budgetMillis,
                                      RetrievalExecutionContext parent) {
        this.deadlineNanos = deadlineNanos;
        this.budgetMillis = budgetMillis;
        this.parent = parent;
    }

    public static RetrievalExecutionContext withBudgetMillis(long budgetMillis) {
        if (budgetMillis <= 0L) {
            return unbounded();
        }
        RetrievalExecutionContext context = new RetrievalExecutionContext(
                System.nanoTime() + budgetMillis * 1_000_000L, budgetMillis, null);
        CompletableFuture.delayedExecutor(budgetMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(context::timeout);
        return context;
    }

    public static RetrievalExecutionContext unbounded() {
        return new RetrievalExecutionContext(Long.MAX_VALUE, 0L, null);
    }

    public RetrievalExecutionContext fork() {
        return forkWithBudgetMillis(0L);
    }

    /**
     * Creates a child with the same request deadline and, when supplied, a
     * shorter stage-local budget. The child can never outlive its parent.
     */
    public RetrievalExecutionContext forkWithBudgetMillis(long requestedBudgetMillis) {
        long childDeadlineNanos = deadlineNanos;
        long childBudgetMillis = budgetMillis;
        if (requestedBudgetMillis > 0L) {
            long localDeadlineNanos = System.nanoTime() + requestedBudgetMillis * 1_000_000L;
            childDeadlineNanos = Math.min(deadlineNanos, localDeadlineNanos);
            childBudgetMillis = Math.min(requestedBudgetMillis, remainingMillis());
        }
        RetrievalExecutionContext child = new RetrievalExecutionContext(childDeadlineNanos, childBudgetMillis, this);
        children.add(child);
        if (childDeadlineNanos != Long.MAX_VALUE && childDeadlineNanos < deadlineNanos) {
            CompletableFuture.delayedExecutor(childBudgetMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(child::timeout);
        }
        State actualState = state();
        if (actualState != State.ACTIVE || (parent != null && !parent.isActive())) {
            child.cancel(actualState == State.ACTIVE ? State.CANCELLED : actualState);
        }
        return child;
    }

    public long remainingMillis() {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        return Math.max(0L, (remainingNanos + 999_999L) / 1_000_000L);
    }

    public boolean isUnbounded() {
        return deadlineNanos == Long.MAX_VALUE;
    }

    public long budgetMillis() {
        return budgetMillis;
    }

    public boolean isActive() {
        return state() == State.ACTIVE && (parent == null || parent.isActive());
    }

    public State state() {
        if (state.get() == State.ACTIVE && remainingMillis() <= 0L) {
            cancel(State.TIMED_OUT);
        }
        return state.get();
    }

    public void register(Future<?> future) {
        if (future == null) return;
        futures.add(future);
        if (!isActive()) future.cancel(true);
    }

    public void unregister(Future<?> future) {
        if (future != null) futures.remove(future);
    }

    public void register(CancellableChatCall call) {
        if (call == null) return;
        calls.add(call);
        if (!isActive()) call.cancel();
    }

    public void unregister(CancellableChatCall call) {
        if (call != null) calls.remove(call);
    }

    public void cancel() {
        cancel(State.CANCELLED);
    }

    public void timeout() {
        cancel(State.TIMED_OUT);
    }

    /**
     * Detach a completed child from its parent without cancelling either side.
     */
    public void close() {
        if (parent != null) {
            parent.children.remove(this);
        }
    }

    private void cancel(State requestedState) {
        if (!state.compareAndSet(State.ACTIVE, requestedState)) return;
        children.forEach(child -> child.cancel(requestedState));
        futures.forEach(future -> future.cancel(true));
        calls.forEach(CancellableChatCall::cancel);
        if (parent != null) parent.children.remove(this);
    }
}
