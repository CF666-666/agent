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

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeOutputExtractorTest {

    @Test
    void shouldDelegateHyperedgeOutputToRegisteredProjector() {
        NodeOutputExtractor extractor = new NodeOutputExtractor(List.of(new HyperEdgeExtractOutputProjector()));
        IngestionContext context = IngestionContext.builder()
                .source(DocumentSource.builder().type(SourceType.FILE).location("ingestion:doc-1").build())
                .chunks(List.of(VectorChunk.builder().content("Fan-1 overload").build()))
                .metadata(Map.of("documentVersion", "v2"))
                .build();

        Map<String, Object> output = extractor.extract(context,
                NodeConfig.builder().nodeType("hyperedge_extract").build());

        assertEquals("ingestion:doc-1", output.get("sourceDocument"));
        assertEquals(1, output.get("chunkCount"));
        assertEquals("v2", output.get("documentVersion"));
    }
}
