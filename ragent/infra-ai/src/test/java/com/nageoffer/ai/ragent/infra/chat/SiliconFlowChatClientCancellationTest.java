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
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SiliconFlowChatClientCancellationTest {

    @Test
    void shouldCancelUnderlyingHttpCall() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            SiliconFlowChatClient client = new SiliconFlowChatClient(
                    new OkHttpClient(), new OkHttpClient(), Runnable::run);
            CancellableChatCall call = client.startChat(
                    ChatRequest.builder().messages(List.of(ChatMessage.user("extract entities"))).build(),
                    target(server));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                var response = executor.submit(call::execute);
                assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();

                call.cancel();

                assertThatThrownBy(() -> response.get(2, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private ModelTarget target(MockWebServer server) {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(server.url("/").toString());
        provider.setApiKey("token");
        provider.setEndpoints(Map.of("chat", "/v1/chat/completions"));
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setProvider("siliconflow");
        candidate.setModel("Qwen/Qwen2.5-7B-Instruct");
        return new ModelTarget("test-chat", candidate, provider);
    }
}
