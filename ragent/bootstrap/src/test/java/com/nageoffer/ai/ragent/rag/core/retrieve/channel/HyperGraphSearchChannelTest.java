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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import com.nageoffer.ai.ragent.rag.core.hypergraph.EntityExtractor;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdge;
import com.nageoffer.ai.ragent.rag.core.hypergraph.IndustrialHyperGraph;
import com.nageoffer.ai.ragent.rag.dto.RetrievalOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HyperGraphSearchChannel 结构化路径构建测试
 */
class HyperGraphSearchChannelTest {

    @Test
    void shouldBuildStructuredPathFromAllFields() {
        HyperEdge edge = HyperEdge.builder()
                .equipment("1号鼓风机")
                .condition("冷却水不足")
                .parameter("电机过载")
                .fault("跳闸")
                .build();

        String path = HyperGraphSearchChannel.buildStructuredPath(edge);
        assertThat(path).isEqualTo("1号鼓风机 → 冷却水不足 → 电机过载 → 跳闸");
    }

    @Test
    void shouldBuildStructuredPathFromPartialFields() {
        HyperEdge edge = HyperEdge.builder()
                .equipment("2号轧机")
                .fault("轴承过热")
                .build();

        String path = HyperGraphSearchChannel.buildStructuredPath(edge);
        assertThat(path).isEqualTo("2号轧机 → 轴承过热");
    }

    @Test
    void shouldReturnEmptyStringForEmptyEdge() {
        HyperEdge edge = HyperEdge.builder().build();
        String path = HyperGraphSearchChannel.buildStructuredPath(edge);
        assertThat(path).isEqualTo("");
    }

    @Test
    void shouldRenderTwoHopPathWithEvidenceMetadata() {
        IndustrialHyperGraph graph = mock(IndustrialHyperGraph.class);
        EntityExtractor extractor = mock(EntityExtractor.class);
        HyperEdge first = HyperEdge.builder().edgeId("edge-1").equipment("1号泵")
                .parameter("轴承温度高").sourceDocument("pump-sop.pdf").sourceChunkId("pump#2").build();
        HyperEdge second = HyperEdge.builder().edgeId("edge-2").condition("轴承温度高")
                .fault("润滑不足").sourceDocument("lube-sop.pdf").sourceChunkId("lube#7").build();
        when(extractor.extractFromQuery("1号泵怎么处理")).thenReturn(Set.of("1号泵"));
        when(graph.findRelationPaths(Set.of("1号泵"), 2, 10)).thenReturn(List.of(
                new IndustrialHyperGraph.RelationPath(List.of(first, second), List.of("轴承温度高"), 2)));
        when(graph.expandToText(first)).thenReturn("1号泵，轴承温度高");
        when(graph.expandToText(second)).thenReturn("轴承温度高，润滑不足");
        HyperGraphSearchChannel channel = new HyperGraphSearchChannel(graph, extractor);

        SearchChannelResult result = channel.search(SearchContext.builder()
                .originalQuestion("1号泵怎么处理")
                .retrievalOptions(RetrievalOptions.defaults())
                .build());

        assertThat(result.getChunks()).hasSize(1);
        assertThat(result.getChunks().get(0).getId()).isEqualTo("edge-1->edge-2");
        assertThat(result.getChunks().get(0).getMetadata())
                .containsEntry("relationHops", 2)
                .containsEntry("bridgeEntities", List.of("轴承温度高"));
        assertThat((List<?>) result.getChunks().get(0).getMetadata().get("relationEvidence"))
                .hasSize(2);
        verify(graph).findRelationPaths(Set.of("1号泵"), 2, 10);
    }
}
