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

package com.nageoffer.ai.ragent.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.FusionProperties;
import com.nageoffer.ai.ragent.rag.dto.Reference;
import com.nageoffer.ai.ragent.rag.dto.ReferenceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 4 端到端编译 + 接口契约验证（纯逻辑，不依赖 Spring）
 */
class Phase4EndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shouldSerializeReferenceToJson() {
        Reference ref = new Reference(
                ReferenceType.HYPERGRAPH,
                "推理路径",
                null,
                "1号鼓风机 → 冷却水不足 → 电机过载 → 跳闸",
                "1号鼓风机，在夏季高温条件下，因冷却水流量异常...",
                Map.of("score", 1.1)
        );

        assertThatCode(() -> {
            String json = MAPPER.writeValueAsString(ref);
            assertThat(json).contains("\"type\":\"HYPERGRAPH\"");
            assertThat(json).contains("\"detail\":\"1号鼓风机 → 冷却水不足 → 电机过载 → 跳闸\"");
            // url=null 由 @JsonInclude(NON_NULL) 压缩，不出现在 JSON 中
            assertThat(json).doesNotContain("\"url\"");
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldSerializeMultipleReferencesToJsonArray() {
        List<Reference> refs = List.of(
                new Reference(ReferenceType.IMAGE, "图纸", "/img/a.jpg", null, "散热片积灰", Map.of()),
                new Reference(ReferenceType.TEXT, "文本引用", null, null, "在温度超过85°C...", Map.of("score", 0.92))
        );

        assertThatCode(() -> {
            String json = MAPPER.writeValueAsString(refs);
            assertThat(json).startsWith("[");
            assertThat(json).endsWith("]");
            assertThat(json).contains("\"type\":\"IMAGE\"");
            assertThat(json).contains("\"type\":\"TEXT\"");
        }).doesNotThrowAnyException();
    }

    @Test
    void shouldFusionPropertiesHaveSensibleDefaults() {
        FusionProperties props = new FusionProperties();
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getWeights()).isNotNull();
    }

    @Test
    void shouldAllSearchChannelTypesExist() {
        // 验证 6 个 ChannelType 枚举项均在 Phase 4 融合权重预期范围内
        assertThat(SearchChannelType.values()).hasSizeGreaterThanOrEqualTo(6);
        for (SearchChannelType type : SearchChannelType.values()) {
            assertThat(type.name()).isNotEmpty();
        }
    }
}
