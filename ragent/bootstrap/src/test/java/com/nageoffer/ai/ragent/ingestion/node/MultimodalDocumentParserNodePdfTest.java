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
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.multimodal.parser.PdfBoxParser;
import com.nageoffer.ai.ragent.multimodal.parser.QwenVLImageParser;
import com.nageoffer.ai.ragent.multimodal.parser.Tess4JParser;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfBoxPageAnalyzer;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageParser;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageRoutingPolicy;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfParseMode;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfTextQualityThresholds;
import com.nageoffer.ai.ragent.multimodal.retrieval.image.ImageIngestionService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MultimodalDocumentParserNodePdfTest {

    @Test
    void routesPdfBytesThroughPageParserAndRetainsTypedResult() throws Exception {
        Tess4JParser tess4J = mock(Tess4JParser.class);
        PdfPageParser pageParser = new PdfPageParser(
                new PdfBoxPageAnalyzer(),
                new PdfPageRoutingPolicy(
                        new PdfTextQualityThresholds("pdf-routing-v1", 10, 0.50, 0.15, 0.02)),
                tess4J,
                144);
        MultimodalDocumentParserNode node = new MultimodalDocumentParserNode(
                mock(PdfBoxParser.class),
                tess4J,
                mock(QwenVLImageParser.class),
                mock(ImageIngestionService.class),
                pageParser);
        IngestionContext context = IngestionContext.builder()
                .taskId("task-pdf")
                .rawBytes(electronicPdf())
                .mimeType("application/pdf")
                .source(DocumentSource.builder().fileName("manual.pdf").build())
                .metadata(Map.of())
                .build();
        NodeConfig config = NodeConfig.builder()
                .settings(new ObjectMapper().createObjectNode().put("pdfMode", "AUTO"))
                .build();

        var result = node.execute(context, config);

        assertTrue(result.isSuccess());
        assertTrue(context.getRawText().contains("equipment maintenance manual"));
        assertNotNull(context.getPdfParseResult());
        assertEquals(PdfParseMode.AUTO, context.getPdfParseResult().mode());
        assertEquals(1, context.getPdfParseResult().pages().size());
    }

    @Test
    void propagatesPageParserFailureWithoutPublishingPartialResult() throws Exception {
        Tess4JParser tess4J = mock(Tess4JParser.class);
        PdfPageParser pageParser = new PdfPageParser(
                new PdfBoxPageAnalyzer(),
                new PdfPageRoutingPolicy(
                        new PdfTextQualityThresholds("pdf-routing-v1", 10, 0.50, 0.15, 0.02)),
                (image, pageNumber) -> {
                    throw new IllegalStateException("native OCR failure");
                },
                144);
        MultimodalDocumentParserNode node = new MultimodalDocumentParserNode(
                mock(PdfBoxParser.class),
                tess4J,
                mock(QwenVLImageParser.class),
                mock(ImageIngestionService.class),
                pageParser);
        IngestionContext context = IngestionContext.builder()
                .taskId("task-pdf-failure")
                .rawBytes(electronicPdf())
                .mimeType("application/pdf")
                .source(DocumentSource.builder().fileName("manual.pdf").build())
                .build();

        var result = node.execute(context, NodeConfig.builder()
                .settings(new ObjectMapper().createObjectNode().put("pdfMode", "OCR"))
                .build());

        assertFalse(result.isSuccess());
        assertEquals(null, context.getPdfParseResult());
        assertEquals(null, context.getRawText());
    }

    private byte[] electronicPdf() throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("equipment maintenance manual");
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
