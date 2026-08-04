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

import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Projects traceability details for hyperedge extraction logs.
 */
@Component
public class HyperEdgeExtractOutputProjector implements NodeOutputProjector {

    @Override
    public String nodeType() {
        return IngestionNodeType.HYPEREDGE_EXTRACT.getValue();
    }

    @Override
    public Map<String, Object> project(IngestionContext context, NodeConfig config) {
        Map<String, Object> output = new LinkedHashMap<>();
        DocumentSource source = context.getSource();
        output.put("sourceDocument", source == null ? null : source.getLocation());
        output.put("chunkCount", context.getChunks() == null ? 0 : context.getChunks().size());
        output.put("documentVersion", context.getMetadata() == null ? null : context.getMetadata().get("documentVersion"));
        return output;
    }
}
