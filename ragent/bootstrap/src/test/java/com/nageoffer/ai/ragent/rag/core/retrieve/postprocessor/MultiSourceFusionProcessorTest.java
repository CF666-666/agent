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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多源加权融合处理器单元测试（纯逻辑，不依赖 Spring）
 */
class MultiSourceFusionProcessorTest {

    private FusionProperties properties;
    private MultiSourceFusionProcessor processor;

    @BeforeEach
    void setUp() {
        properties = new FusionProperties();
        Map<String, Double> weights = new HashMap<>();
        weights.put("VECTOR_GLOBAL", 1.0);
        weights.put("INTENT_DIRECTED", 1.0);
        weights.put("IMAGE_SEMANTIC", 0.9);
        weights.put("HYPERGRAPH", 1.1);
        properties.setWeights(weights);
        properties.setEnabled(true);
        processor = new MultiSourceFusionProcessor(properties);
    }

    @Test
    void shouldWeightAndSortMultipleSources() {
        List<RetrievedChunk> chunks = new ArrayList<>();
        chunks.add(chunk("txt1", "VECTOR_GLOBAL", 0.9f));
        chunks.add(chunk("txt2", "VECTOR_GLOBAL", 0.5f));
        chunks.add(chunk("img1", "IMAGE_SEMANTIC", 0.8f));
        chunks.add(chunk("img2", "IMAGE_SEMANTIC", 0.4f));
        chunks.add(chunk("hyp1", "HYPERGRAPH", 1.0f));
        chunks.add(chunk("hyp2", "HYPERGRAPH", 0.3f));

        List<SearchChannelResult> results = List.of(
                result(SearchChannelType.VECTOR_GLOBAL, chunks.get(0), chunks.get(1)),
                result(SearchChannelType.IMAGE_SEMANTIC, chunks.get(2), chunks.get(3)),
                result(SearchChannelType.HYPERGRAPH, chunks.get(4), chunks.get(5))
        );

        List<RetrievedChunk> merged = processor.process(chunks, results,
                SearchContext.builder().topK(10).build());

        assertThat(merged).hasSize(6);
        // HYPERGRAPH 权重 1.1 > VECTOR_GLOBAL 1.0 > IMAGE_SEMANTIC 0.9
        // 归一化后各通道内部排序不变，加权后 HYPERGRAPH 组整体最高
        assertThat(merged.get(0).getScore()).isGreaterThanOrEqualTo(merged.get(1).getScore());
        assertThat(merged.get(merged.size() - 1).getScore()).isNotNull();
    }

    @Test
    void shouldPreserveRawScoreForSingleChunk() {
        List<RetrievedChunk> chunks = List.of(chunk("only", "IMAGE_SEMANTIC", 0.35f));
        List<SearchChannelResult> results = List.of(result(SearchChannelType.IMAGE_SEMANTIC, chunks.get(0)));

        List<RetrievedChunk> merged = processor.process(chunks, results,
                SearchContext.builder().topK(5).build());

        // 单 chunk 保留原始分数仅加权，不放大到 1.0
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).getScore()).isEqualTo(0.35f * 0.9f);
    }

    @Test
    void shouldPreserveRawScoreForAllSameScores() {
        List<RetrievedChunk> chunks = new ArrayList<>();
        chunks.add(chunk("a", "VECTOR_GLOBAL", 0.5f));
        chunks.add(chunk("b", "VECTOR_GLOBAL", 0.5f));
        chunks.add(chunk("c", "VECTOR_GLOBAL", 0.5f));

        List<SearchChannelResult> results = List.of(
                result(SearchChannelType.VECTOR_GLOBAL, chunks.get(0), chunks.get(1), chunks.get(2))
        );

        List<RetrievedChunk> merged = processor.process(chunks, results,
                SearchContext.builder().topK(5).build());

        // 全部相同分数 > 保留原始 * 权重，不设 1.0
        assertThat(merged).hasSize(3);
        for (RetrievedChunk c : merged) {
            assertThat(c.getScore()).isEqualTo(0.5f * 1.0f);
        }
    }

    @Test
    void shouldFallbackSourceFromResults() {
        // chunk 无 metadata.source，需要从 SearchChannelResult 兜底
        RetrievedChunk noSource = chunk(null, null, 0.7f);
        List<RetrievedChunk> chunks = List.of(noSource);
        List<SearchChannelResult> results = List.of(result(SearchChannelType.HYPERGRAPH, noSource));

        List<RetrievedChunk> merged = processor.process(chunks, results,
                SearchContext.builder().topK(5).build());

        assertThat(merged).hasSize(1);
        // source 应被兜底补全
        assertThat(merged.get(0).getMetadata()).containsEntry("source", "HYPERGRAPH");
    }

    @Test
    void shouldSkipWhenDisabled() {
        properties.setEnabled(false);
        List<RetrievedChunk> chunks = List.of(chunk("a", "VECTOR_GLOBAL", 0.8f));
        List<RetrievedChunk> result = processor.process(chunks, List.of(),
                SearchContext.builder().topK(5).build());

        // isEnabled=false → 原样返回，不做任何处理
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getScore()).isEqualTo(0.8f);
    }

    // ================== helpers ==================

    private static RetrievedChunk chunk(String id, String source, Float score) {
        Map<String, Object> meta = new HashMap<>();
        if (source != null) meta.put("source", source);
        return RetrievedChunk.builder().id(id).text("text-" + id).score(score).metadata(meta).build();
    }

    private static SearchChannelResult result(SearchChannelType type, RetrievedChunk... chunks) {
        return SearchChannelResult.builder()
                .channelType(type).channelName(type.name())
                .chunks(List.of(chunks)).latencyMs(0L).build();
    }
}
