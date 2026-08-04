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

package com.nageoffer.ai.ragent.rag.core.hypergraph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndustrialHyperGraphNormalizationTest {

    @Test
    void shouldMatchAliasesAgainstCanonicalDocumentEntities() {
        ConfigurableIndustrialEntityNormalizer normalizer = new ConfigurableIndustrialEntityNormalizer();
        normalizer.setAliases(Map.of("风机1号", "1号鼓风机", "跳机", "过载跳闸"));
        IndustrialHyperGraph graph = new IndustrialHyperGraphImpl(null, normalizer,
                new ConfigurableHyperEdgeMatchScorer(normalizer));
        graph.addHyperedges(List.of(HyperEdge.builder()
                .equipment("1号鼓风机")
                .fault("过载跳闸")
                .build()));

        IndustrialHyperGraph.SubgraphMatchResult result = graph.matchSubgraph(Set.of("风机1号", "跳机"), 1).get(0);

        assertEquals(2, result.matchCount());
        assertEquals("1号鼓风机", result.hyperEdge().getEquipment());
        assertEquals("过载跳闸", result.hyperEdge().getFault());
    }

    @Test
    void shouldReturnTwoHopPathWithBridgeAndSourceEvidence() {
        ConfigurableIndustrialEntityNormalizer normalizer = new ConfigurableIndustrialEntityNormalizer();
        normalizer.setAliases(Map.of("泵一号", "1号泵"));
        IndustrialHyperGraph graph = new IndustrialHyperGraphImpl(null, normalizer,
                new ConfigurableHyperEdgeMatchScorer(normalizer));
        graph.addHyperedges(List.of(
                HyperEdge.builder().edgeId("edge-pump").equipment("1号泵").parameter("轴承温度高")
                        .sourceDocument("pump-sop.pdf").sourceChunkId("pump#2").build(),
                HyperEdge.builder().edgeId("edge-bearing").condition("轴承温度高").fault("润滑不足")
                        .sourceDocument("lube-sop.pdf").sourceChunkId("lube#7").build()));

        IndustrialHyperGraph.RelationPath path = graph.findRelationPaths(Set.of("泵一号"), 2, 10).stream()
                .filter(candidate -> candidate.hopCount() == 2)
                .findFirst()
                .orElseThrow();

        assertEquals(List.of("edge-pump", "edge-bearing"),
                path.hyperEdges().stream().map(HyperEdge::getEdgeId).toList());
        assertEquals(List.of("轴承温度高"), path.bridgeEntities());
        assertEquals(List.of("pump-sop.pdf", "lube-sop.pdf"),
                path.hyperEdges().stream().map(HyperEdge::getSourceDocument).toList());
    }

    @Test
    void shouldRankEquipmentMatchAboveConditionOnlyMatch() {
        ConfigurableIndustrialEntityNormalizer normalizer = new ConfigurableIndustrialEntityNormalizer();
        IndustrialHyperGraph graph = new IndustrialHyperGraphImpl(null, normalizer,
                new ConfigurableHyperEdgeMatchScorer(normalizer));
        graph.addHyperedges(List.of(
                HyperEdge.builder().edgeId("condition-edge").condition("高温工况").fault("润滑不足").build(),
                HyperEdge.builder().edgeId("equipment-edge").equipment("1号泵").fault("机械故障").build()));

        List<IndustrialHyperGraph.SubgraphMatchResult> matches = graph.matchSubgraph(
                Set.of("高温工况", "1号泵"), 2);

        assertEquals(List.of("equipment-edge", "condition-edge"),
                matches.stream().map(match -> match.hyperEdge().getEdgeId()).toList());
    }
}
