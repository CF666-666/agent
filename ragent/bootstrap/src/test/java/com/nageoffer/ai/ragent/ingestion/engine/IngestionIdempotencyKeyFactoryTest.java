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
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class IngestionIdempotencyKeyFactoryTest {

    @Test
    void shouldUseSourceVersionAndPipelineDefinitionToCreateStableKey() {
        IngestionIdempotencyKeyFactory factory = new IngestionIdempotencyKeyFactory(new ObjectMapper());
        DocumentSource source = DocumentSource.builder().type(SourceType.FILE).location("manual.pdf").fileName("manual.pdf").build();
        PipelineDefinition pipeline = PipelineDefinition.builder().id("p1").name("ingestion").nodes(List.of()).build();

        String first = factory.create(source, "v1".getBytes(StandardCharsets.UTF_8), pipeline, "industrial");
        String same = factory.create(source, "v1".getBytes(StandardCharsets.UTF_8), pipeline, "industrial");
        String changedContent = factory.create(source, "v2".getBytes(StandardCharsets.UTF_8), pipeline, "industrial");

        assertEquals(first, same);
        assertNotEquals(first, changedContent);
        assertEquals(20, factory.deterministicChunkId(first, 1).length());
    }
}
