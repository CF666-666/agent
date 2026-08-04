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
        IndustrialHyperGraph graph = new IndustrialHyperGraphImpl(null, normalizer);
        graph.addHyperedges(List.of(HyperEdge.builder()
                .equipment("1号鼓风机")
                .fault("过载跳闸")
                .build()));

        IndustrialHyperGraph.SubgraphMatchResult result = graph.matchSubgraph(Set.of("风机1号", "跳机"), 1).get(0);

        assertEquals(2, result.matchCount());
        assertEquals("1号鼓风机", result.hyperEdge().getEquipment());
        assertEquals("过载跳闸", result.hyperEdge().getFault());
    }
}
