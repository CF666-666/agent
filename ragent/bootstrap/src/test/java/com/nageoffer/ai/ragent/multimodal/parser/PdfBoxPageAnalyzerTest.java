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

import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfBoxPageAnalyzer;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageAnalysis;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfBoxPageAnalyzerTest {

    private final PdfBoxPageAnalyzer analyzer = new PdfBoxPageAnalyzer();

    @Test
    void analyzesTextAndImageCoveragePerPageWithoutDependingOnFileSuffix() throws Exception {
        Path suffixlessPdf = Files.createTempFile("mixed-pdf-", ".tmp");
        suffixlessPdf.toFile().deleteOnExit();
        createMixedPdf(suffixlessPdf);

        List<PdfPageAnalysis> pages = analyzer.analyze(suffixlessPdf.toFile());

        assertEquals(List.of(1, 2), pages.stream().map(PdfPageAnalysis::pageNumber).toList());
        assertTrue(pages.get(0).nativeText().contains("native equipment manual"));
        assertTrue(pages.get(0).nativeCharacterCount() >= 23);
        assertEquals(0.0, pages.get(0).visualCoverageRatio(), 0.001);
        assertTrue(pages.get(1).nativeText().isBlank());
        assertTrue(pages.get(1).visualCoverageRatio() > 0.90);
    }

    @Test
    void computesUnionAreaForDisjointImagesInsteadOfTheirBoundingBox() throws Exception {
        Path pdf = Files.createTempFile("disjoint-images-", ".pdf");
        pdf.toFile().deleteOnExit();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            PDImageXObject image = PDImageXObject.createFromByteArray(document, pngBytes(), "tile");
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(image, 0, 0, 100, 100);
                stream.drawImage(image, 100, 100, 100, 100);
            }
            document.save(pdf.toFile());
        }

        List<PdfPageAnalysis> pages = analyzer.analyze(pdf.toFile());

        assertEquals(0.50, pages.get(0).visualCoverageRatio(), 0.01);
    }

    @Test
    void avoidsDoubleCountingOverlappingImages() throws Exception {
        Path pdf = Files.createTempFile("overlapping-images-", ".pdf");
        pdf.toFile().deleteOnExit();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            PDImageXObject image = PDImageXObject.createFromByteArray(document, pngBytes(), "tile");
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(image, 0, 0, 100, 100);
                stream.drawImage(image, 50, 50, 100, 100);
            }
            document.save(pdf.toFile());
        }

        assertEquals(0.4375, analyzer.analyze(pdf.toFile()).get(0).visualCoverageRatio(), 0.01);
    }

    @Test
    void respectsCropBoxAndActiveClippingPath() throws Exception {
        Path pdf = Files.createTempFile("clipped-image-", ".pdf");
        pdf.toFile().deleteOnExit();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 300));
            page.setCropBox(new PDRectangle(50, 50, 200, 200));
            document.addPage(page);
            PDImageXObject image = PDImageXObject.createFromByteArray(document, pngBytes(), "scan");
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.addRect(50, 50, 100, 100);
                stream.clip();
                stream.drawImage(image, 50, 50, 200, 200);
            }
            document.save(pdf.toFile());
        }

        assertEquals(0.25, analyzer.analyze(pdf.toFile()).get(0).visualCoverageRatio(), 0.01);
    }

    private void createMixedPdf(Path output) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage textPage = new PDPage(PDRectangle.A4);
            document.addPage(textPage);
            try (PDPageContentStream stream = new PDPageContentStream(document, textPage)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText("native equipment manual");
                stream.endText();
            }

            PDPage imagePage = new PDPage(PDRectangle.A4);
            document.addPage(imagePage);
            PDImageXObject image = PDImageXObject.createFromByteArray(
                    document, pngBytes(), "scanned-page");
            try (PDPageContentStream stream = new PDPageContentStream(document, imagePage)) {
                stream.drawImage(
                        image,
                        imagePage.getCropBox().getLowerLeftX(),
                        imagePage.getCropBox().getLowerLeftY(),
                        imagePage.getCropBox().getWidth(),
                        imagePage.getCropBox().getHeight());
            }
            document.save(output.toFile());
        }
    }

    private byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.drawString("scan", 1, 12);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
