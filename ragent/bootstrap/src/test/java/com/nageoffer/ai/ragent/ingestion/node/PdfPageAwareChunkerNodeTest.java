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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.chunk.ChunkingMode;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.DocumentParseResult;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseReason;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseResult;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseStatus;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseStrategy;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfParseMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PdfPageAwareChunkerNodeTest {

    @Test
    void chunksEachCompletedPageSeparatelyAndRetainsPageEvidence() {
        ChunkingStrategy strategy = mock(ChunkingStrategy.class);
        when(strategy.chunk(any(), any())).thenAnswer(invocation -> List.of(
                VectorChunk.builder().chunkId("local-0").index(0)
                        .content(invocation.getArgument(0)).metadata(Map.of("origin", "strategy")).build()));
        ChunkingStrategyFactory factory = mock(ChunkingStrategyFactory.class);
        when(factory.requireStrategy(ChunkingMode.FIXED_SIZE)).thenReturn(strategy);
        ChunkEmbeddingService embedding = mock(ChunkEmbeddingService.class);
        ChunkerNode node = new ChunkerNode(new ObjectMapper(), factory, embedding);
        IngestionContext context = IngestionContext.builder()
                .taskId("task-1")
                .rawText("page one\n\npage two")
                .pdfParseResult(documentResult())
                .build();
        NodeConfig config = NodeConfig.builder()
                .settings(new ObjectMapper().createObjectNode()
                        .put("strategy", "FIXED_SIZE")
                        .put("chunkSize", 100)
                        .put("overlapSize", 0))
                .build();

        var result = node.execute(context, config);

        assertTrue(result.isSuccess());
        assertEquals(List.of(0, 1), context.getChunks().stream().map(VectorChunk::getIndex).toList());
        assertEquals(2, context.getChunks().stream().map(VectorChunk::getChunkId).distinct().count());
        assertEquals(List.of(1, 2), context.getChunks().stream()
                .map(chunk -> chunk.getMetadata().get("pageNumber")).toList());
        assertEquals(List.of("PDFBOX", "OCR"), context.getChunks().stream()
                .map(chunk -> chunk.getMetadata().get("pageStrategy")).toList());
        verify(embedding).embed(context.getChunks(), null);

        List<String> firstRunIds = context.getChunks().stream().map(VectorChunk::getChunkId).toList();
        node.execute(context, config);
        assertEquals(firstRunIds, context.getChunks().stream().map(VectorChunk::getChunkId).toList());

        context.setTaskId("task-2");
        node.execute(context, config);
        org.junit.jupiter.api.Assertions.assertNotEquals(
                firstRunIds, context.getChunks().stream().map(VectorChunk::getChunkId).toList());
    }

    @Test
    void rejectsDocumentLevelEnhancementThatCannotRetainPageEvidence() {
        ChunkingStrategyFactory factory = mock(ChunkingStrategyFactory.class);
        ChunkEmbeddingService embedding = mock(ChunkEmbeddingService.class);
        ChunkerNode node = new ChunkerNode(new ObjectMapper(), factory, embedding);
        IngestionContext context = IngestionContext.builder()
                .taskId("task-enhanced-pdf")
                .rawText("page one\n\npage two")
                .enhancedText("rewritten whole document")
                .pdfParseResult(documentResult())
                .build();

        var result = node.execute(context, NodeConfig.builder().build());

        assertFalse(result.isSuccess());
        org.mockito.Mockito.verifyNoInteractions(factory, embedding);
    }

    private DocumentParseResult documentResult() {
        return new DocumentParseResult(
                "manual.pdf",
                PdfParseMode.AUTO,
                "pdf-routing-v1",
                20,
                List.of(
                        new PageParseResult(1, PageParseStrategy.PDFBOX,
                                PageParseReason.NATIVE_TEXT_USABLE, PageParseStatus.COMPLETED,
                                8, 0, 2, "page one"),
                        new PageParseResult(2, PageParseStrategy.OCR,
                                PageParseReason.VISUAL_CONTENT_WITHOUT_TEXT, PageParseStatus.COMPLETED,
                                0, 8, 18, "page two")));
    }
}
