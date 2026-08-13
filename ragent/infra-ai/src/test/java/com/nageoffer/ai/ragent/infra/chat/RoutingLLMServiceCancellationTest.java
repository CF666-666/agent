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

package com.nageoffer.ai.ragent.infra.chat;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingLLMServiceCancellationTest {

    @Test
    void shouldExposeHandleAndCancelTransportBeforeFirstPacket() throws Exception {
        ModelSelector selector = mock(ModelSelector.class);
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setProvider("test");
        ModelTarget target = new ModelTarget("chat-test", candidate, new AIModelProperties.ProviderConfig());
        when(selector.selectChatCandidates(false)).thenReturn(List.of(target));
        when(healthStore.allowCall("chat-test")).thenReturn(true);

        AtomicBoolean transportCancelled = new AtomicBoolean();
        CountDownLatch transportStarted = new CountDownLatch(1);
        ChatClient client = new ChatClient() {
            @Override public String provider() { return "test"; }
            @Override public String chat(ChatRequest request, ModelTarget ignored) { return ""; }
            @Override public StreamCancellationHandle streamChat(
                    ChatRequest request, StreamCallback callback, ModelTarget ignored) {
                transportStarted.countDown();
                return () -> transportCancelled.set(true);
            }
        };
        RoutingLLMService service = new RoutingLLMService(
                selector, healthStore, mock(ModelRoutingExecutor.class), List.of(client));
        CountDownLatch handleReady = new CountDownLatch(1);
        AtomicReference<StreamCancellationHandle> exposed = new AtomicReference<>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var invocation = executor.submit(() -> service.streamChat(
                    ChatRequest.builder().messages(List.of(ChatMessage.user("question"))).build(),
                    mock(StreamCallback.class),
                    handle -> {
                        exposed.set(handle);
                        handleReady.countDown();
                    }));

            assertThat(handleReady.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(transportStarted.await(1, TimeUnit.SECONDS)).isTrue();
            exposed.get().cancel();

            assertThat(transportCancelled).isTrue();
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> invocation.get(1, TimeUnit.SECONDS))
                    .as("first-packet wait must be released by cancellation")
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(java.util.concurrent.CancellationException.class);
        } finally {
            executor.shutdownNow();
        }
    }
}
