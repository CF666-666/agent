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
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.multimodal.parser.PdfBoxParser;
import com.nageoffer.ai.ragent.multimodal.parser.QwenVLImageParser;
import com.nageoffer.ai.ragent.multimodal.parser.Tess4JParser;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.OcrPageReader;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfBoxPageAnalyzer;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageParser;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageRoutingPolicy;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfTextQualityThresholds;
import com.nageoffer.ai.ragent.multimodal.retrieval.image.ImageIngestionService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PdfIngestionFlowIntegrationTest {

    @Test
    void mixedPdfProducesNativeAndOcrChunksWithPageEvidence() throws Exception {
        OcrPageReader ocr = (image, pageNumber) -> {
            assertEquals(2, pageNumber);
            assertTrue(image.getWidth() > 0);
            assertTrue(image.getHeight() > 0);
            return "equipment inspection procedure";
        };
        PdfPageParser pageParser = new PdfPageParser(
                new PdfBoxPageAnalyzer(),
                new PdfPageRoutingPolicy(
                        new PdfTextQualityThresholds("pdf-routing-v1", 10, 0.50, 0.15, 0.02)),
                ocr,
                200);
        Tess4JParser tess4J = mock(Tess4JParser.class);
        MultimodalDocumentParserNode parserNode = new MultimodalDocumentParserNode(
                mock(PdfBoxParser.class),
                tess4J,
                mock(QwenVLImageParser.class),
                mock(ImageIngestionService.class),
                pageParser);
        ChunkingStrategy chunking = mock(ChunkingStrategy.class);
        when(chunking.chunk(any(), any())).thenAnswer(invocation -> List.of(
                VectorChunk.builder().index(0).content(invocation.getArgument(0)).build()));
        ChunkingStrategyFactory factory = mock(ChunkingStrategyFactory.class);
        when(factory.requireStrategy(ChunkingMode.FIXED_SIZE)).thenReturn(chunking);
        ChunkerNode chunkerNode = new ChunkerNode(
                new ObjectMapper(), factory, mock(ChunkEmbeddingService.class));
        IngestionContext context = IngestionContext.builder()
                .taskId("mixed-pdf-task")
                .rawBytes(mixedPdf())
                .mimeType("application/pdf")
                .source(DocumentSource.builder().fileName("mixed-manual.pdf").build())
                .build();
        ObjectMapper mapper = new ObjectMapper();
        NodeConfig parserConfig = NodeConfig.builder()
                .settings(mapper.createObjectNode().put("pdfMode", "AUTO"))
                .build();
        NodeConfig chunkConfig = NodeConfig.builder()
                .settings(mapper.createObjectNode()
                        .put("strategy", "FIXED_SIZE")
                        .put("chunkSize", 200)
                        .put("overlapSize", 0))
                .build();

        assertTrue(parserNode.execute(context, parserConfig).isSuccess());
        assertTrue(chunkerNode.execute(context, chunkConfig).isSuccess());

        assertEquals(List.of(1, 2), context.getChunks().stream()
                .map(chunk -> chunk.getMetadata().get("pageNumber")).toList());
        assertEquals(List.of("PDFBOX", "OCR"), context.getChunks().stream()
                .map(chunk -> chunk.getMetadata().get("pageStrategy")).toList());
        assertTrue(context.getChunks().get(0).getContent().contains("native equipment manual"));
        assertTrue(context.getChunks().get(1).getContent().contains("equipment inspection procedure"));
    }

    private byte[] mixedPdf() throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage nativePage = new PDPage(PDRectangle.A4);
            document.addPage(nativePage);
            try (PDPageContentStream stream = new PDPageContentStream(document, nativePage)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("native equipment manual");
                stream.endText();
            }

            PDPage scannedPage = new PDPage(PDRectangle.A4);
            document.addPage(scannedPage);
            BufferedImage scan = new BufferedImage(1200, 300, BufferedImage.TYPE_INT_RGB);
            var graphics = scan.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, scan.getWidth(), scan.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(chineseFont(96));
            graphics.drawString("设备检修规程", 80, 190);
            graphics.dispose();
            var image = LosslessFactory.createFromImage(document, scan);
            try (PDPageContentStream stream = new PDPageContentStream(document, scannedPage)) {
                stream.drawImage(image, 0, 0,
                        scannedPage.getCropBox().getWidth(), scannedPage.getCropBox().getHeight());
            }
            scan.flush();
            document.save(output);
            return output.toByteArray();
        }
    }

    private Font chineseFont(int size) {
        String[] candidates = {"Microsoft YaHei", "SimSun", "Noto Sans CJK SC", "Dialog"};
        for (String candidate : candidates) {
            Font font = new Font(candidate, Font.PLAIN, size);
            if (font.canDisplayUpTo("设备检修规程") == -1) {
                return font;
            }
        }
        throw new IllegalStateException("No Chinese test font available");
    }
}
