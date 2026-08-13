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
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalExecutionContextTest {

    @Test
    void shouldCancelRegisteredModelCallWhenRequestIsCancelled() {
        RetrievalExecutionContext context = RetrievalExecutionContext.withBudgetMillis(1_000L);
        AtomicBoolean cancelled = new AtomicBoolean();
        context.register(new CancellableChatCall() {
            @Override
            public String execute() {
                return "unused";
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });

        context.cancel();

        assertThat(context.state()).isEqualTo(RetrievalExecutionContext.State.CANCELLED);
        assertThat(cancelled).isTrue();
    }

    @Test
    void shouldCancelRegisteredModelCallWhenDeadlineExpires() throws Exception {
        RetrievalExecutionContext context = RetrievalExecutionContext.withBudgetMillis(30L);
        AtomicBoolean cancelled = new AtomicBoolean();
        context.register(new CancellableChatCall() {
            @Override
            public String execute() {
                return "unused";
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });

        TimeUnit.MILLISECONDS.sleep(100L);

        assertThat(context.state()).isEqualTo(RetrievalExecutionContext.State.TIMED_OUT);
        assertThat(cancelled).isTrue();
    }

    @Test
    void shouldInterruptRegisteredMcpStyleTaskWhenDeadlineExpires() throws Exception {
        RetrievalExecutionContext context = RetrievalExecutionContext.withBudgetMillis(30L);
        CountDownLatch interrupted = new CountDownLatch(1);
        FutureTask<Void> task = new FutureTask<>(() -> {
            try {
                TimeUnit.SECONDS.sleep(10L);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            context.register(task);
            executor.execute(task);

            assertThat(interrupted.await(1L, TimeUnit.SECONDS)).isTrue();
            assertThat(task.isCancelled()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldTimeOutShortStageBudgetWithoutCancellingParentRequest() throws Exception {
        RetrievalExecutionContext request = RetrievalExecutionContext.withBudgetMillis(1_000L);
        RetrievalExecutionContext rewrite = request.forkWithBudgetMillis(30L);
        AtomicBoolean cancelled = new AtomicBoolean();
        rewrite.register(new CancellableChatCall() {
            @Override
            public String execute() {
                return "unused";
            }

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });

        TimeUnit.MILLISECONDS.sleep(100L);

        assertThat(rewrite.state()).isEqualTo(RetrievalExecutionContext.State.TIMED_OUT);
        assertThat(request.state()).isEqualTo(RetrievalExecutionContext.State.ACTIVE);
        assertThat(cancelled).isTrue();
    }
}
