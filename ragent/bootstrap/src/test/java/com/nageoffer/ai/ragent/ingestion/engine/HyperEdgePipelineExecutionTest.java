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

package com.nageoffer.ai.ragent.ingestion.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeEdge;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.node.IngestionNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HyperEdgePipelineExecutionTest {

    @Test
    void shouldExecuteHyperedgeExtractionBetweenChunkingAndIndexing() {
        List<String> executedNodeTypes = new ArrayList<>();
        ConditionEvaluator conditionMatcher = new ConditionEvaluator(new ObjectMapper());
        IngestionEngine engine = new IngestionEngine(List.of(
                recordingNode("chunker", executedNodeTypes),
                recordingNode("hyperedge_extract", executedNodeTypes),
                recordingNode("indexer", executedNodeTypes)),
                conditionMatcher,
                new ConditionalPipelineRouteResolver(conditionMatcher),
                new NodeOutputExtractor(),
                new NodeExecutionRunner());
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(
                        node("chunk", "chunker"),
                        node("hyperedge", "hyperedge_extract"),
                        node("index", "indexer")))
                .edges(List.of(
                        edge("chunk", "hyperedge"),
                        edge("hyperedge", "index")))
                .build();

        IngestionContext result = engine.execute(pipeline, IngestionContext.builder().build());

        assertEquals(IngestionStatus.COMPLETED, result.getStatus());
        assertEquals(List.of("chunker", "hyperedge_extract", "indexer"), executedNodeTypes);
        assertEquals(List.of("chunker", "hyperedge_extract", "indexer"),
                result.getLogs().stream().map(log -> log.getNodeType()).toList());
    }

    private static IngestionNode recordingNode(String nodeType, List<String> executedNodeTypes) {
        return new IngestionNode() {
            @Override
            public String getNodeType() {
                return nodeType;
            }

            @Override
            public NodeResult execute(IngestionContext context, NodeConfig config) {
                executedNodeTypes.add(nodeType);
                return NodeResult.ok();
            }
        };
    }

    private static NodeConfig node(String nodeId, String nodeType) {
        return NodeConfig.builder().nodeId(nodeId).nodeType(nodeType).build();
    }

    private static NodeEdge edge(String fromNodeId, String toNodeId) {
        return NodeEdge.builder().fromNodeId(fromNodeId).toNodeId(toNodeId).defaultEdge(true).build();
    }
}
