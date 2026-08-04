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

import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeEdge;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineConditionMatcher;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineGraph;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Default routing policy: choose the first matched conditional edge (already
 * priority-sorted by the graph), otherwise choose the default edge.
 */
@Component
@RequiredArgsConstructor
public class ConditionalPipelineRouteResolver implements PipelineRouteResolver {

    private final PipelineConditionMatcher conditionMatcher;

    @Override
    public String resolveNextNodeId(PipelineGraph graph, String nodeId, IngestionContext context) {
        NodeEdge defaultEdge = null;
        for (NodeEdge edge : graph.outgoingEdges(nodeId)) {
            if (edge.isDefaultEdge()) {
                defaultEdge = edge;
                continue;
            }
            if (conditionMatcher.matches(context, edge.getCondition())) {
                return edge.getToNodeId();
            }
        }
        return defaultEdge == null ? null : defaultEdge.getToNodeId();
    }
}
