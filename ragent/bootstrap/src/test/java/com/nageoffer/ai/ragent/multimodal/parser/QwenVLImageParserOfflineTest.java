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

package com.nageoffer.ai.ragent.multimodal.parser;

import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QwenVLImageParserOfflineTest {

    @Test
    void shouldBuildSiliconFlowOpenAiCompatibleRequestAndReadResponse() {
        String requestJson = QwenVLImageParser.buildRequest("image-data", target(
                "siliconflow", "Qwen/Qwen3-VL-32B-Instruct"));
        JsonObject request = com.google.gson.JsonParser.parseString(requestJson).getAsJsonObject();

        assertThat(request.get("model").getAsString()).isEqualTo("Qwen/Qwen3-VL-32B-Instruct");
        assertThat(request.getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonArray("content").get(0).getAsJsonObject()
                .getAsJsonObject("image_url").get("url").getAsString())
                .isEqualTo("data:image/jpeg;base64,image-data");
        assertThat(QwenVLImageParser.extractText("""
                {"choices":[{"message":{"content":"硅基流动视觉描述"}}]}
                """, "siliconflow")).isEqualTo("硅基流动视觉描述");
    }

    @Test
    void shouldKeepBaiLianDashScopeProtocolAvailableByConfiguration() {
        String requestJson = QwenVLImageParser.buildRequest("image-data", target("bailian", "qwen-vl-max"));
        JsonObject request = com.google.gson.JsonParser.parseString(requestJson).getAsJsonObject();

        assertThat(request.get("model").getAsString()).isEqualTo("qwen-vl-max");
        assertThat(request.getAsJsonObject("input").getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonArray("content").get(0).getAsJsonObject().get("image").getAsString())
                .isEqualTo("data:image/jpeg;base64,image-data");
        assertThat(QwenVLImageParser.extractText("""
                {"output":{"choices":[{"message":{"content":[{"text":"百炼视觉描述"}]}}]}}
                """, "bailian")).isEqualTo("百炼视觉描述");
    }

    @Test
    void shouldRejectUnsupportedProviderAndInvalidResponse() {
        assertThatThrownBy(() -> QwenVLImageParser.buildRequest("image-data", target("unknown", "model")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported vision provider");
        assertThatThrownBy(() -> QwenVLImageParser.extractText("{}", "siliconflow"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing choices");
    }

    private ModelTarget target(String providerId, String model) {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setProvider(providerId);
        candidate.setModel(model);
        AIModelProperties.ProviderConfig provider = new AIModelProperties.ProviderConfig();
        provider.setEndpoints(Map.of("multimodal", "/v1/chat/completions"));
        return new ModelTarget("vision", candidate, provider);
    }
}
