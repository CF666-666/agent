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

package com.nageoffer.ai.ragent.ingestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskCheckpointStoreTest {

    @Test
    void shouldRedactSensitivePipelineSettingsWithoutChangingOtherConfiguration() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TaskCheckpointStore store = new TaskCheckpointStore(null, null, objectMapper, null);
        PipelineDefinition pipeline = PipelineDefinition.builder()
                .nodes(List.of(NodeConfig.builder().nodeId("fetch").nodeType("fetcher")
                        .settings(objectMapper.readTree("""
                                {"endpoint":"https://example.test","apiKey":"secret-value",
                                 "nested":{"accessToken":"another-secret"}}
                                """))
                        .build()))
                .build();

        PipelineDefinition snapshot = store.sanitizePipelineSnapshot(pipeline);
        JsonNode settings = snapshot.getNodes().get(0).getSettings();

        assertEquals("https://example.test", settings.get("endpoint").asText());
        assertEquals("[REDACTED]", settings.get("apiKey").asText());
        assertEquals("[REDACTED]", settings.get("nested").get("accessToken").asText());
    }
}
