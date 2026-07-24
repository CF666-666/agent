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
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 电子文档解析器（PDF / Word / Excel）
 * <p>
 * PDF 使用 PDFBox 提取文本和表格；Word/Excel 委托现有 Apache Tika 处理。
 */
@Slf4j
@Component
public class PdfBoxParser implements MultimodalDocumentParser {

    @Override
    public ParseResult parse(File file, FileType fileType) {
        String fileName = file.getName();
        log.info("开始解析电子文档: {} (类型: {})", fileName, fileType);

        String text = extractText(file, fileName);
        Map<String, String> metadata = new HashMap<>();
        metadata.put("parser", "PDFBox");
        metadata.put("fileName", fileName);
        metadata.put("fileSize", String.valueOf(file.length()));

        return ParseResult.builder()
                .sourceFile(file.getAbsolutePath())
                .fileType(fileType)
                .textContent(text)
                .metadata(metadata)
                .build();
    }

    @Override
    public List<ParseResult> batchParse(List<File> files) {
        List<ParseResult> results = new ArrayList<>();
        for (File file : files) {
            FileType type = detectType(file.getName());
            results.add(parse(file, type));
        }
        return results;
    }

    /**
     * 根据扩展名判断文件类型
     */
    private FileType detectType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return FileType.PDF_ELECTRONIC;
        }
        return FileType.PDF_ELECTRONIC; // Word/Excel 也走此解析器，统一处理
    }

    /**
     * 提取文本内容
     */
    private String extractText(File file, String fileName) {
        String lower = fileName.toLowerCase();

        if (lower.endsWith(".pdf")) {
            return extractPdfText(file);
        }
        // Word/Excel 等格式暂委托 Tika 处理（已存在于依赖中）
        return extractWithTika(file);
    }

    /**
     * PDFBox 提取文本（含排序以保持阅读顺序）
     */
    private String extractPdfText(File file) {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            stripper.setParagraphStart("\n");
            stripper.setParagraphEnd("\n");

            int pageCount = document.getNumberOfPages();
            log.info("PDF 共 {} 页: {}", pageCount, file.getName());

            String text = stripper.getText(document);
            log.info("PDF 提取文本 {} 字符", text.length());
            return text;
        } catch (IOException e) {
            log.error("PDF 解析失败: {}", file.getName(), e);
            throw new RuntimeException("PDF 解析失败: " + file.getName(), e);
        }
    }

    /**
     * 委托 Tika 提取 Word/Excel 等格式的文本
     */
    private String extractWithTika(File file) {
        try {
            // Tika 在现有依赖中已存在，直接使用 AutoDetectParser
            org.apache.tika.parser.AutoDetectParser parser =
                    new org.apache.tika.parser.AutoDetectParser();
            org.apache.tika.sax.BodyContentHandler handler =
                    new org.apache.tika.sax.BodyContentHandler(-1);
            org.apache.tika.metadata.Metadata metadata = new org.apache.tika.metadata.Metadata();

            try (java.io.InputStream input = new java.io.FileInputStream(file)) {
                parser.parse(input, handler, metadata);
            }

            String text = handler.toString();
            log.info("Tika 提取文本 {} 字符: {}", text.length(), file.getName());
            return text;
        } catch (Exception e) {
            log.error("Tika 解析失败: {}", file.getName(), e);
            throw new RuntimeException("Tika 解析失败: " + file.getName(), e);
        }
    }
}
