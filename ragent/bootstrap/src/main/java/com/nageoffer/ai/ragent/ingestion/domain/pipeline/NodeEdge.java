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

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A directed connection between two ingestion nodes.
 *
 * <p>Conditional edges are evaluated by descending priority. A default edge is evaluated only
 * after no conditional edge matches.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeEdge {

    private String edgeId;

    private String fromNodeId;

    private String toNodeId;

    private JsonNode condition;

    private int priority;

    private boolean defaultEdge;
}
