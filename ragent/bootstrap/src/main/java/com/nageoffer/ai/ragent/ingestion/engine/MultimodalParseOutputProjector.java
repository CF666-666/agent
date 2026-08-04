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
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Projects multimodal parser state without coupling it to the orchestration core.
 */
@Component
public class MultimodalParseOutputProjector implements NodeOutputProjector {

    @Override
    public String nodeType() {
        return IngestionNodeType.MULTIMODAL_PARSE.getValue();
    }

    @Override
    public Map<String, Object> project(IngestionContext context, NodeConfig config) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mimeType", context.getMimeType());
        output.put("rawText", context.getRawText());
        output.put("metadata", context.getMetadata());
        return output;
    }
}
