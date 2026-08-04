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
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineConditionMatcher;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.node.IngestionNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestionEngineRouteFailureTest {

    @Test
    void shouldNotifyDurableCheckpointAfterEachResolvedNode() {
        IngestionNode successfulNode = new IngestionNode() {
            @Override
            public String getNodeType() {
                return "fetcher";
            }

            @Override
            public NodeResult execute(IngestionContext context, NodeConfig config) {
                return NodeResult.ok();
            }
        };
        ConditionEvaluator conditionMatcher = new ConditionEvaluator(new ObjectMapper());
        IngestionEngine engine = new IngestionEngine(List.of(successfulNode), conditionMatcher,
                new ConditionalPipelineRouteResolver(conditionMatcher), new NodeOutputExtractor(List.of()), new NodeExecutionRunner());
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(node("start"), node("end")))
                .edges(List.of(NodeEdge.builder().fromNodeId("start").toNodeId("end").defaultEdge(true).build()))
                .build();
        List<String> checkpoints = new ArrayList<>();

        engine.execute(pipeline, IngestionContext.builder().build(), null,
                (context, completedNodeId, nextNodeId) -> checkpoints.add(completedNodeId + ":" + nextNodeId));

        assertEquals(List.of("start:end", "end:null"), checkpoints);
    }

    @Test
    void shouldPersistFailureInContextWhenRouteEvaluationThrows() throws Exception {
        PipelineConditionMatcher failingMatcher = (context, condition) -> {
            throw new IllegalStateException("broken route condition");
        };
        IngestionNode successfulNode = new IngestionNode() {
            @Override
            public String getNodeType() {
                return "fetcher";
            }

            @Override
            public NodeResult execute(IngestionContext context, NodeConfig config) {
                return NodeResult.ok();
            }
        };
        IngestionEngine engine = new IngestionEngine(List.of(successfulNode), failingMatcher,
                new ConditionalPipelineRouteResolver(failingMatcher), new NodeOutputExtractor(List.of()), new NodeExecutionRunner());
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(node("start"), node("end")))
                .edges(List.of(NodeEdge.builder().fromNodeId("start").toNodeId("end")
                        .condition(new ObjectMapper().readTree("true")).build()))
                .build();

        IngestionContext result = engine.execute(pipeline, IngestionContext.builder().build());

        assertEquals(IngestionStatus.FAILED, result.getStatus());
        assertEquals("broken route condition", result.getError().getMessage());
        assertEquals(2, result.getLogs().size());
        assertEquals(false, result.getLogs().get(1).isSuccess());
    }

    @Test
    void shouldFailTaskAndPersistNodeLogWhenSpelConditionIsInvalid() throws Exception {
        IngestionNode successfulNode = new IngestionNode() {
            @Override
            public String getNodeType() {
                return "fetcher";
            }

            @Override
            public NodeResult execute(IngestionContext context, NodeConfig config) {
                return NodeResult.ok();
            }
        };
        ConditionEvaluator conditionMatcher = new ConditionEvaluator(new ObjectMapper());
        IngestionEngine engine = new IngestionEngine(List.of(successfulNode), conditionMatcher,
                new ConditionalPipelineRouteResolver(conditionMatcher), new NodeOutputExtractor(List.of()), new NodeExecutionRunner());
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(NodeConfig.builder().nodeId("start").nodeType("fetcher")
                        .condition(new ObjectMapper().readTree("\"broken [ spel\""))
                        .build()))
                .build();

        IngestionContext result = engine.execute(pipeline, IngestionContext.builder().build());

        assertEquals(IngestionStatus.FAILED, result.getStatus());
        assertEquals(true, result.getError().getMessage().startsWith("SpEL condition evaluation failed:"));
        assertEquals(1, result.getLogs().size());
        assertEquals(false, result.getLogs().get(0).isSuccess());
    }

    private NodeConfig node(String nodeId) {
        return NodeConfig.builder().nodeId(nodeId).nodeType("fetcher").build();
    }
}
