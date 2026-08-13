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

package com.nageoffer.ai.ragent.multimodal.parser;

import com.nageoffer.ai.ragent.multimodal.parser.pdf.DocumentParseResult;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.OcrPageReader;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseStrategy;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseStatus;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfBoxPageAnalyzer;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageParser;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageRoutingPolicy;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfParseMode;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfTextQualityThresholds;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfPageParserTest {

    @Test
    void autoOnlyRendersAndOcrsPagesSelectedByTheRoutingPolicy() throws Exception {
        Path pdf = twoPagePdf();
        RecordingOcr ocr = new RecordingOcr("scanned equipment page");
        PdfPageParser parser = parser(ocr);

        DocumentParseResult result = parser.parse(pdf.toFile(), PdfParseMode.AUTO);

        assertEquals(List.of(2), ocr.pageNumbers);
        assertEquals(List.of(PageParseStrategy.PDFBOX, PageParseStrategy.OCR),
                result.pages().stream().map(page -> page.strategy()).toList());
        assertTrue(result.mergedText().contains("native equipment page"));
        assertTrue(result.mergedText().contains("scanned equipment page"));
    }

    @Test
    void emptyOcrResultFailsTheWholeDocument() throws Exception {
        PdfPageParser parser = parser(new RecordingOcr("   "));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> parser.parse(twoPagePdf().toFile(), PdfParseMode.AUTO));

        assertTrue(failure.getMessage().contains("page 2"));
    }

    @Test
    void forcedModesDoNotImplicitlyFallback() throws Exception {
        Path pdf = twoPagePdf();
        RecordingOcr ocr = new RecordingOcr("forced OCR text");
        PdfPageParser parser = parser(ocr);

        DocumentParseResult forcedPdfBox = parser.parse(pdf.toFile(), PdfParseMode.PDFBOX);
        assertTrue(ocr.pageNumbers.isEmpty());
        assertEquals(List.of(PageParseStrategy.PDFBOX, PageParseStrategy.PDFBOX),
                forcedPdfBox.pages().stream().map(page -> page.strategy()).toList());

        DocumentParseResult forcedOcr = parser.parse(pdf.toFile(), PdfParseMode.OCR);
        assertEquals(List.of(1, 2), ocr.pageNumbers);
        assertEquals(List.of(PageParseStrategy.OCR, PageParseStrategy.OCR),
                forcedOcr.pages().stream().map(page -> page.strategy()).toList());
    }

    @Test
    void ocrFailureIncludesPageNumberAndFailsTheWholeDocument() throws Exception {
        PdfPageParser parser = parser((image, pageNumber) -> {
            throw new IllegalStateException("OCR engine not ready");
        });

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> parser.parse(twoPagePdf().toFile(), PdfParseMode.AUTO));

        assertTrue(failure.getMessage().contains("page 2"));
        assertTrue(failure.getCause().getMessage().contains("not ready"));
    }

    @Test
    void preservesOrderedTypedEvidenceAndTrueEmptyPages() throws Exception {
        RecordingOcr ocr = new RecordingOcr("ocr text");
        DocumentParseResult result = parser(ocr).parse(threePagePdf().toFile(), PdfParseMode.AUTO);

        assertEquals(List.of(1, 2, 3),
                result.pages().stream().map(page -> page.pageNumber()).toList());
        assertEquals(List.of(PageParseStatus.COMPLETED, PageParseStatus.COMPLETED, PageParseStatus.EMPTY),
                result.pages().stream().map(page -> page.status()).toList());
        assertTrue(result.pages().get(0).nativeCharacterCount() > 0);
        assertEquals(0, result.pages().get(0).ocrCharacterCount());
        assertEquals(8, result.pages().get(1).ocrCharacterCount());
        assertEquals(0, result.pages().get(2).nativeCharacterCount());
        assertEquals(0, result.pages().get(2).ocrCharacterCount());
        assertTrue(result.pages().stream().allMatch(page -> page.durationMillis() >= 0));
        assertTrue(result.durationMillis() >= 0);
    }

    private PdfPageParser parser(OcrPageReader ocr) {
        return new PdfPageParser(
                new PdfBoxPageAnalyzer(),
                new PdfPageRoutingPolicy(
                        new PdfTextQualityThresholds("pdf-routing-v1", 10, 0.50, 0.15, 0.02)),
                ocr,
                144);
    }

    private Path twoPagePdf() throws Exception {
        return createPdf(false);
    }

    private Path threePagePdf() throws Exception {
        return createPdf(true);
    }

    private Path createPdf(boolean appendEmptyPage) throws Exception {
        Path path = Files.createTempFile("page-parser-", ".pdf");
        path.toFile().deleteOnExit();
        try (PDDocument document = new PDDocument()) {
            PDPage nativePage = new PDPage(PDRectangle.A4);
            document.addPage(nativePage);
            try (PDPageContentStream stream = new PDPageContentStream(document, nativePage)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("native equipment page");
                stream.endText();
            }
            PDPage scannedPage = new PDPage(PDRectangle.A4);
            document.addPage(scannedPage);
            BufferedImage scan = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
            PDImageXObject image = PDImageXObject.createFromByteArray(
                    document, pngBytes(scan), "scan");
            try (PDPageContentStream stream = new PDPageContentStream(document, scannedPage)) {
                stream.drawImage(
                        image,
                        0,
                        0,
                        scannedPage.getCropBox().getWidth(),
                        scannedPage.getCropBox().getHeight());
            }
            if (appendEmptyPage) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(path.toFile());
        }
        return path;
    }

    private byte[] pngBytes(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static final class RecordingOcr implements OcrPageReader {

        private final List<Integer> pageNumbers = new ArrayList<>();
        private final String text;

        private RecordingOcr(String text) {
            this.text = text;
        }

        @Override
        public String read(BufferedImage pageImage, int pageNumber) {
            pageNumbers.add(pageNumber);
            return text;
        }
    }
}
