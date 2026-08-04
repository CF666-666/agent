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

package com.nageoffer.ai.ragent.ingestion.domain.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.engine.ConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineGraphTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConditionEvaluator conditionEvaluator = new ConditionEvaluator(objectMapper);

    @Test
    void shouldChooseHighestPriorityMatchedEdgeThenFallbackToDefaultEdge() throws Exception {
        PipelineGraph graph = PipelineGraph.of(PipelineDefinition.builder()
                .nodes(List.of(node("fetch"), node("pdf-parser"), node("low-priority-parser"), node("generic-parser")))
                .edges(List.of(
                        conditionalEdge("pdf", "fetch", "pdf-parser", 100,
                                "{\"field\":\"mimeType\",\"operator\":\"eq\",\"value\":\"application/pdf\"}"),
                        conditionalEdge("low-priority", "fetch", "low-priority-parser", 10, "\"mimeType != null\""),
                        NodeEdge.builder().edgeId("fallback").fromNodeId("fetch").toNodeId("generic-parser")
                                .defaultEdge(true).build()))
                .build());

        assertEquals("fetch", graph.startNodeId());
        assertEquals("pdf-parser", graph.resolveNextNodeId("fetch",
                IngestionContext.builder().mimeType("application/pdf").build(), conditionEvaluator));
        assertEquals("low-priority-parser", graph.resolveNextNodeId("fetch",
                IngestionContext.builder().mimeType("image/png").build(), conditionEvaluator));
        assertEquals("generic-parser", graph.resolveNextNodeId("fetch",
                IngestionContext.builder().build(), conditionEvaluator));
    }

    @Test
    void shouldAdaptLegacyNextNodeIdWhenNoExplicitOutgoingEdgeExists() {
        PipelineGraph graph = PipelineGraph.of(PipelineDefinition.builder()
                .nodes(List.of(node("fetch", "parser"), node("parser")))
                .build());

        assertEquals("fetch", graph.startNodeId());
        assertEquals("parser", graph.resolveNextNodeId("fetch", IngestionContext.builder().build(), conditionEvaluator));
        assertEquals(null, graph.resolveNextNodeId("parser", IngestionContext.builder().build(), conditionEvaluator));
    }

    @Test
    void shouldRejectCycleBeforeExecution() {
        ClientException exception = assertThrows(ClientException.class, () -> PipelineGraph.of(PipelineDefinition.builder()
                .nodes(List.of(node("a"), node("b")))
                .edges(List.of(
                        NodeEdge.builder().fromNodeId("a").toNodeId("b").defaultEdge(true).build(),
                        NodeEdge.builder().fromNodeId("b").toNodeId("a").defaultEdge(true).build()))
                .build()));

        assertEquals(true, exception.getMessage().contains("cycle"));
    }

    @Test
    void shouldRejectAnEmptyPipeline() {
        ClientException exception = assertThrows(ClientException.class,
                () -> PipelineGraph.of(PipelineDefinition.builder().nodes(List.of()).build()));

        assertEquals(true, exception.getMessage().contains("at least one node"));
    }

    @Test
    void shouldRejectAmbiguousConditionalPriorities() throws Exception {
        ClientException exception = assertThrows(ClientException.class, () -> PipelineGraph.of(PipelineDefinition.builder()
                .nodes(List.of(node("start"), node("left"), node("right")))
                .edges(List.of(
                        conditionalEdge("left-edge", "start", "left", 10, "true"),
                        conditionalEdge("right-edge", "start", "right", 10, "false")))
                .build()));

        assertEquals(true, exception.getMessage().contains("same priority"));
    }

    @Test
    void shouldRejectInvalidRegexAtConfigurationTime() throws Exception {
        ClientException exception = assertThrows(ClientException.class, () -> PipelineGraph.of(PipelineDefinition.builder()
                .nodes(List.of(node("start"), node("end")))
                .edges(List.of(conditionalEdge("invalid-regex", "start", "end", 1,
                        "{\"field\":\"mimeType\",\"operator\":\"regex\",\"value\":\"[\"}")))
                .build()));

        assertEquals(true, exception.getMessage().contains("Invalid regex"));
    }

    private NodeConfig node(String nodeId) {
        return node(nodeId, null);
    }

    private NodeConfig node(String nodeId, String nextNodeId) {
        return NodeConfig.builder().nodeId(nodeId).nodeType("fetcher").nextNodeId(nextNodeId).build();
    }

    private NodeEdge conditionalEdge(String edgeId,
                                     String fromNodeId,
                                     String toNodeId,
                                     int priority,
                                     String condition) throws Exception {
        return NodeEdge.builder()
                .edgeId(edgeId)
                .fromNodeId(fromNodeId)
                .toNodeId(toNodeId)
                .priority(priority)
                .condition(objectMapper.readTree(condition))
                .build();
    }
}
