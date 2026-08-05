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

package com.nageoffer.ai.ragent.ingestion.node;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdge;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdgeDocumentStore;
import com.nageoffer.ai.ragent.rag.core.hypergraph.IndustrialHyperGraph;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HyperEdgeExtractNodeTest {

    @Test
    void shouldExtractEachChunkAndAttachTraceableEvidenceBeforeIndexing() {
        IndustrialHyperGraph hyperGraph = mock(IndustrialHyperGraph.class);
        HyperEdgeDocumentStore hyperEdgeStore = mock(HyperEdgeDocumentStore.class);
        HyperEdge edge = HyperEdge.builder().equipment("Fan-1").fault("overload trip").build();
        when(hyperGraph.extractHyperedges(anyString(), eq("sop/fan-maintenance.pdf"))).thenReturn(List.of(edge));
        HyperEdgeExtractNode node = new HyperEdgeExtractNode(hyperGraph, hyperEdgeStore);
        IngestionContext context = IngestionContext.builder()
                .taskId("task-1")
                .idempotencyKey("doc-v2")
                .source(DocumentSource.builder().type(SourceType.FILE).location("sop/fan-maintenance.pdf").build())
                .metadata(Map.of("documentVersion", "v2"))
                .chunks(List.of(VectorChunk.builder().index(3).content("Fan-1 overload trip")
                        .metadata(Map.of("pageNumber", "12")).build()))
                .build();

        NodeResult result = node.execute(context, NodeConfig.builder().build());

        ArgumentCaptor<List<HyperEdge>> captured = ArgumentCaptor.forClass(List.class);
        verify(hyperEdgeStore).replaceDocument(eq("sop/fan-maintenance.pdf"), captured.capture());
        verify(hyperGraph).replaceDocumentHyperedges(eq("sop/fan-maintenance.pdf"), eq(captured.getValue()));
        HyperEdge persisted = captured.getValue().get(0);
        assertTrue(result.isSuccess());
        assertEquals("sop/fan-maintenance.pdf", persisted.getSourceDocument());
        assertEquals("doc-v2#chunk-3", persisted.getSourceChunkId());
        assertEquals(3, persisted.getSourceChunkIndex());
        assertEquals(12, persisted.getSourcePage());
        assertEquals("v2", persisted.getDocumentVersion());
    }

    @Test
    void shouldFailWithoutChunks() {
        IndustrialHyperGraph hyperGraph = mock(IndustrialHyperGraph.class);
        HyperEdgeDocumentStore hyperEdgeStore = mock(HyperEdgeDocumentStore.class);
        HyperEdgeExtractNode node = new HyperEdgeExtractNode(hyperGraph, hyperEdgeStore);

        NodeResult result = node.execute(IngestionContext.builder().build(), NodeConfig.builder().build());

        assertFalse(result.isSuccess());
        verifyNoInteractions(hyperGraph);
        verifyNoInteractions(hyperEdgeStore);
    }

    @Test
    void shouldFailWhenEveryChunkIsBlankInsteadOfReplacingExistingHyperedges() {
        IndustrialHyperGraph hyperGraph = mock(IndustrialHyperGraph.class);
        HyperEdgeDocumentStore hyperEdgeStore = mock(HyperEdgeDocumentStore.class);
        HyperEdgeExtractNode node = new HyperEdgeExtractNode(hyperGraph, hyperEdgeStore);
        IngestionContext context = IngestionContext.builder()
                .taskId("task-blank")
                .chunks(List.of(VectorChunk.builder().content("   ").build()))
                .build();

        NodeResult result = node.execute(context, NodeConfig.builder().build());

        assertFalse(result.isSuccess());
        verifyNoInteractions(hyperGraph);
        verifyNoInteractions(hyperEdgeStore);
    }

    @Test
    void shouldNotIndexPartialEdgesWhenExtractionFails() {
        IndustrialHyperGraph hyperGraph = mock(IndustrialHyperGraph.class);
        HyperEdgeDocumentStore hyperEdgeStore = mock(HyperEdgeDocumentStore.class);
        when(hyperGraph.extractHyperedges(anyString(), anyString()))
                .thenThrow(new IllegalStateException("extractor unavailable"));
        HyperEdgeExtractNode node = new HyperEdgeExtractNode(hyperGraph, hyperEdgeStore);
        IngestionContext context = IngestionContext.builder()
                .taskId("task-2")
                .chunks(List.of(VectorChunk.builder().content("Fan-1 overload trip").build()))
                .build();

        NodeResult result = node.execute(context, NodeConfig.builder().build());

        assertFalse(result.isSuccess());
        verify(hyperGraph).extractHyperedges("Fan-1 overload trip", "ingestion:task-2");
        verify(hyperGraph, org.mockito.Mockito.never()).replaceDocumentHyperedges(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
        verifyNoInteractions(hyperEdgeStore);
    }
}
