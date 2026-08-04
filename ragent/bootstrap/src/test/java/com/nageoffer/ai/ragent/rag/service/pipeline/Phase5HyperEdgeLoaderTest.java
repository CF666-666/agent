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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdgeDocumentStore;
import com.nageoffer.ai.ragent.rag.core.hypergraph.IndustrialHyperGraph;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Phase5HyperEdgeLoaderTest {

    @Test
    void shouldNotFallbackToLegacyJsonlWhenPersistentStoreIsEmpty() throws Exception {
        Path legacyFile = Path.of("data", "hypergraph", "hyperedges.jsonl");
        boolean legacyFileAlreadyExists = Files.exists(legacyFile);
        byte[] originalContents = legacyFileAlreadyExists ? Files.readAllBytes(legacyFile) : null;
        Files.createDirectories(legacyFile.getParent());
        Files.writeString(legacyFile, "{\"equipment\":\"legacy fan\"}\n", StandardCharsets.UTF_8);

        IndustrialHyperGraph hyperGraph = mock(IndustrialHyperGraph.class);
        HyperEdgeDocumentStore store = mock(HyperEdgeDocumentStore.class);
        Environment environment = mock(Environment.class);
        when(store.loadActiveHyperedges()).thenReturn(List.of());
        Phase5HyperEdgeLoader loader = new Phase5HyperEdgeLoader(hyperGraph, store, environment);

        try {
            loader.run();

            verifyNoInteractions(hyperGraph);
        } finally {
            if (legacyFileAlreadyExists) {
                Files.write(legacyFile, originalContents);
            } else {
                Files.deleteIfExists(legacyFile);
            }
        }
    }
}
