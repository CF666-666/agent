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

package com.nageoffer.ai.ragent.infra.rerank;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SiliconFlowRerankClientTest {

    @Test
    void shouldUseSiliconFlowRerankRequestAndPreserveMetadata() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setBody("{\"results\":[{\"index\":1,\"relevance_score\":0.98},{\"index\":0,\"relevance_score\":0.12}]}"));
            server.start();
            SiliconFlowRerankClient client = new SiliconFlowRerankClient(new OkHttpClient());

            List<RetrievedChunk> result = client.rerank("pump maintenance", List.of(
                    chunk("first", "unrelated", 0.1F),
                    chunk("second", "pump maintenance procedure", 0.2F),
                    chunk("third", "other maintenance", 0.3F)), 2,
                    target(server));

            RecordedRequest request = server.takeRequest();
            assertThat(request.getPath()).isEqualTo("/v1/rerank");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token");
            assertThat(request.getBody().readUtf8())
                    .contains("\"model\":\"Qwen/Qwen3-Reranker-8B\"")
                    .contains("\"query\":\"pump maintenance\"")
                    .contains("\"documents\"")
                    .contains("\"top_n\":2");
            assertThat(result).extracting(RetrievedChunk::getId).containsExactly("second", "first");
            assertThat(result.get(0).getScore()).isEqualTo(0.98F);
            assertThat(result.get(0).getMetadata()).containsEntry("source", "test");
        }
    }

    private ModelTarget target(MockWebServer server) {
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setUrl(server.url("/").toString());
        provider.setApiKey("token");
        provider.setEndpoints(Map.of("rerank", "/v1/rerank"));
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setProvider("siliconflow");
        candidate.setModel("Qwen/Qwen3-Reranker-8B");
        return new ModelTarget("qwen3-reranker-8b", candidate, provider);
    }

    private RetrievedChunk chunk(String id, String text, float score) {
        return RetrievedChunk.builder().id(id).text(text).score(score)
                .metadata(new HashMap<>(Map.of("source", "test"))).build();
    }
}
