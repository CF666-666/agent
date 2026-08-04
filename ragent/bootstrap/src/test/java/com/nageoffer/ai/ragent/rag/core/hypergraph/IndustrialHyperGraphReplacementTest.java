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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndustrialHyperGraphReplacementTest {

    @Test
    void shouldReplaceOldDocumentEdgesWithoutRemovingOtherDocuments() {
        ConfigurableIndustrialEntityNormalizer normalizer = new ConfigurableIndustrialEntityNormalizer();
        IndustrialHyperGraph graph = new IndustrialHyperGraphImpl(null, normalizer,
                new ConfigurableHyperEdgeMatchScorer(normalizer));
        graph.addHyperedges(List.of(
                HyperEdge.builder().sourceDocument("doc-a").equipment("fan-a").fault("old-fault").build(),
                HyperEdge.builder().sourceDocument("doc-b").equipment("pump-b").fault("pump-fault").build()));

        graph.replaceDocumentHyperedges("doc-a", List.of(
                HyperEdge.builder().sourceDocument("doc-a").equipment("fan-a").fault("new-fault").build()));

        assertEquals(0, graph.matchSubgraph(Set.of("old-fault"), 10).size());
        assertEquals("new-fault", graph.matchSubgraph(Set.of("new-fault"), 10).get(0).hyperEdge().getFault());
        assertEquals("pump-b", graph.matchSubgraph(Set.of("pump-b"), 10).get(0).hyperEdge().getEquipment());
    }
}
