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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PdfBoxParser 自测
 */
class PdfBoxParserTest {

    private static final PdfBoxParser parser = new PdfBoxParser();
    private static File testPdf;

    @BeforeAll
    static void setUp() throws Exception {
        // 生成一个测试 PDF
        testPdf = File.createTempFile("test-", ".pdf");
        testPdf.deleteOnExit();

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 16);
                cs.newLineAtOffset(50, 700);
                cs.showText("2# Rolling Mill Main Bearing Maintenance Manual");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 660);
                cs.showText("Equipment Model: ZJ-2000-01");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 640);
                cs.showText("Maintenance Cycle: Quarterly, Monthly in Winter");
                cs.endText();

                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 610);
                cs.showText("Lubricant Spec: Shell Omala S4 GX 320");
                cs.endText();
            }
            doc.save(testPdf);
        }
        System.out.println("生成测试 PDF: " + testPdf.getAbsolutePath());
    }

    @Test
    @DisplayName("PDF 文本提取 - 含中文和工业内容")
    void testParseElectronicPdf() {
        ParseResult result = parser.parse(testPdf, FileType.PDF_ELECTRONIC);

        assertNotNull(result);
        assertNotNull(result.getTextContent());
        assertTrue(result.getTextContent().contains("Rolling Mill"), "应包含设备名");
        assertTrue(result.getTextContent().contains("Shell Omala"), "应包含润滑油规格");
        assertTrue(result.getTextContent().length() > 50, "提取文本应 >50 字符");

        assertEquals(FileType.PDF_ELECTRONIC, result.getFileType());
        assertNotNull(result.getMetadata().get("parser"));
        assertNotNull(result.getMetadata().get("fileName"));

        System.out.println("=== 提取的文本内容 ===");
        System.out.println(result.getTextContent());
        System.out.println("=====================");
    }

    @Test
    @DisplayName("非 PDF 文件委托 Tika（Markdown 文本提取）")
    void testParseNonPdf() throws Exception {
        // 用临时 Markdown 文件测试 Tika 回退
        Path mdPath = Files.createTempFile("test-", ".md");
        mdPath.toFile().deleteOnExit();
        Files.writeString(mdPath, "# 开票信息\n\n开票抬头: XX钢铁集团\n税号: 91310000XXXXXXXX");

        ParseResult result = parser.parse(mdPath.toFile(), FileType.PDF_ELECTRONIC);

        assertNotNull(result);
        assertNotNull(result.getTextContent());
        assertTrue(result.getTextContent().contains("开票信息"), "应包含 Markdown 标题");
        assertTrue(result.getTextContent().contains("XX钢铁集团"), "应包含内容");
    }
}
