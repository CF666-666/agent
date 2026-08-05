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

import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdge;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdgeDocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DemoHyperEdgeImporterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void doesNothingWithoutExplicitImportFlag() throws Exception {
        Environment environment = mock(Environment.class);
        HyperEdgeDocumentStore store = mock(HyperEdgeDocumentStore.class);
        DemoHyperEdgeImporter importer = new DemoHyperEdgeImporter(environment, store,
                temporaryDirectory.resolve("missing.jsonl"));

        importer.run();

        verifyNoInteractions(store);
    }

    @Test
    void replacesEachSourceDocumentWithItsFixtureEdges() throws Exception {
        Path fixture = temporaryDirectory.resolve("hyperedges.jsonl");
        Files.writeString(fixture, """
                {"edgeId":"edge-1","equipment":"pump","sourceDocument":"faq/pump"}
                {"edgeId":"edge-2","equipment":"pump","sourceDocument":"faq/pump"}
                {"edgeId":"edge-3","equipment":"fan","sourceDocument":"faq/fan","extendedEntities":[{"label":"role","value":"operator"}]}
                """);
        Environment environment = mock(Environment.class);
        HyperEdgeDocumentStore store = mock(HyperEdgeDocumentStore.class);
        when(environment.getProperty(DemoHyperEdgeImporter.IMPORT_PROP)).thenReturn("true");
        DemoHyperEdgeImporter importer = new DemoHyperEdgeImporter(environment, store, fixture);

        importer.run();

        ArgumentCaptor<List<HyperEdge>> edgesCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).replaceDocument(org.mockito.ArgumentMatchers.eq("faq/pump"), edgesCaptor.capture());
        assertEquals(List.of("edge-1", "edge-2"), edgesCaptor.getValue().stream().map(HyperEdge::getEdgeId).toList());
        verify(store).replaceDocument(org.mockito.ArgumentMatchers.eq("faq/fan"), edgesCaptor.capture());
        assertEquals("operator", edgesCaptor.getValue().get(0).getExtendedEntities().get(0).value());
    }

    @Test
    void rejectsMalformedFixturesBeforeWritingAnyDocument() throws Exception {
        Path fixture = temporaryDirectory.resolve("hyperedges.jsonl");
        Files.writeString(fixture, "{" + "\"edgeId\":\"edge-1\"}" + System.lineSeparator());
        Environment environment = mock(Environment.class);
        HyperEdgeDocumentStore store = mock(HyperEdgeDocumentStore.class);
        when(environment.getProperty(DemoHyperEdgeImporter.IMPORT_PROP)).thenReturn("true");
        DemoHyperEdgeImporter importer = new DemoHyperEdgeImporter(environment, store, fixture);

        assertThrows(IllegalArgumentException.class, importer::run);
        verifyNoInteractions(store);
    }
}
