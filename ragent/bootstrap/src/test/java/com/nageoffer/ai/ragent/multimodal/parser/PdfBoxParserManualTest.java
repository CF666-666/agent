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

import com.nageoffer.ai.ragent.multimodal.parser.dto.FileType;
import com.nageoffer.ai.ragent.multimodal.parser.dto.ParseResult;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.File;

/**
 * PdfBoxParser 手动测试
 */
public class PdfBoxParserManualTest {

    public static void main(String[] args) throws Exception {
        // 1. 生成一个英文测试 PDF（标准字体不支持中文，但真实PDF的内嵌字体可正常解析中文）
        File testPdf = File.createTempFile("test-ragent-", ".pdf");
        testPdf.deleteOnExit();

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                cs.newLineAtOffset(50, 700);
                cs.showText("Rolling Mill No.2 - Bearing Maintenance Manual");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 660);
                cs.showText("Equipment Model: ZJ-2000-01");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 640);
                cs.showText("Lubricant: Shell Omala S4 GX 320");
                cs.endText();
            }
            doc.save(testPdf);
        }
        System.out.println("[OK] Generated test PDF: " + testPdf.getAbsolutePath());

        // 2. Parse with PdfBoxParser
        PdfBoxParser parser = new PdfBoxParser();
        ParseResult result = parser.parse(testPdf, FileType.PDF_ELECTRONIC);

        // 3. Output result
        System.out.println("\n=== PDF Parse Result ===");
        System.out.println("Source file: " + result.getSourceFile());
        System.out.println("File type:   " + result.getFileType());
        System.out.println("Parser:      " + result.getMetadata().get("parser"));
        System.out.println("Text length: " + result.getTextContent().length() + " chars");
        System.out.println("\n--- Extracted Text ---");
        System.out.println(result.getTextContent());
        System.out.println("======================");

        // 4. Assertions
        String text = result.getTextContent();
        boolean pass1 = text.contains("Rolling Mill");
        boolean pass2 = text.contains("Shell Omala");
        boolean pass3 = text.contains("ZJ-2000-01");
        boolean pass4 = result.getTextContent().length() > 50;

        System.out.println("\n=== Verifications ===");
        System.out.println("Contains 'Rolling Mill':   " + pass1);
        System.out.println("Contains 'Shell Omala':    " + pass2);
        System.out.println("Contains 'ZJ-2000-01':     " + pass3);
        System.out.println("Text length > 50 chars:    " + pass4);
        System.out.println("ALL PASSED:                " + (pass1 && pass2 && pass3 && pass4));
    }
}
