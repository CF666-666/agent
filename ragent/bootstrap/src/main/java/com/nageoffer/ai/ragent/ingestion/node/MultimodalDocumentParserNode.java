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

import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.multimodal.parser.MultimodalDocumentParser;
import com.nageoffer.ai.ragent.multimodal.parser.PdfBoxParser;
import com.nageoffer.ai.ragent.multimodal.parser.QwenVLImageParser;
import com.nageoffer.ai.ragent.multimodal.parser.Tess4JParser;
import com.nageoffer.ai.ragent.multimodal.parser.dto.FileType;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageParser;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfParseMode;
import com.nageoffer.ai.ragent.multimodal.retrieval.image.ImageIngestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 多模态文档解析节点
 * <p>
 * 接入 ETL Pipeline，位于 ParserNode 之后。
 * 从 {@link IngestionContext#getRawBytes()} 读取原始字节，根据 MIME/扩展名路由到对应
 * Parser（PdfBox / Tess4J / Qwen-VL），将解析后的文本写回 context。
 */
@Slf4j
@Component
public class MultimodalDocumentParserNode implements IngestionNode {

    private final PdfBoxParser pdfBoxParser;
    private final Tess4JParser tess4JParser;
    private final QwenVLImageParser qwenVLImageParser;
    private final ImageIngestionService imageIngestionService;
    private final PdfPageParser pdfPageParser;

    /** 显式路由表：FileType → Parser */
    private final Map<FileType, MultimodalDocumentParser> routing;

    public MultimodalDocumentParserNode(PdfBoxParser pdfBoxParser,
                                        Tess4JParser tess4JParser,
                                        QwenVLImageParser qwenVLImageParser,
                                        ImageIngestionService imageIngestionService,
                                        PdfPageParser pdfPageParser) {
        this.pdfBoxParser = pdfBoxParser;
        this.tess4JParser = tess4JParser;
        this.qwenVLImageParser = qwenVLImageParser;
        this.imageIngestionService = imageIngestionService;
        this.pdfPageParser = pdfPageParser;
        this.routing = Map.of(
                FileType.PDF_ELECTRONIC, pdfBoxParser,
                FileType.PDF_SCANNED,    tess4JParser,
                FileType.IMAGE_DRAWING,  qwenVLImageParser,
                FileType.IMAGE_PHOTO,    qwenVLImageParser
        );
    }

    @Override
    public String getNodeType() {
        return IngestionNodeType.MULTIMODAL_PARSE.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        byte[] rawBytes = context.getRawBytes();
        if (rawBytes == null || rawBytes.length == 0) {
            log.debug("无原始字节数据，跳过多模态解析");
            return NodeResult.ok();
        }

        FileType fileType = detectType(context);
        MultimodalDocumentParser parser = routing.get(fileType);
        if (parser == null) {
            log.debug("无对应多模态解析器: {}", fileType);
            return NodeResult.ok();
        }

        log.info("多模态解析: {} ({} bytes) → {}", fileType.name(), rawBytes.length, parser.getClass().getSimpleName());

        // rawBytes → temp File → parse → 写回 context → finally delete
        Path tmp = null;
        try {
            tmp = Files.createTempFile("ragent-mm-", ".tmp");
            Files.write(tmp, rawBytes);
            File tmpFile = tmp.toFile();

            if (fileType == FileType.PDF_ELECTRONIC || fileType == FileType.PDF_SCANNED) {
                PdfParseMode mode = resolvePdfMode(config);
                var pageResult = pdfPageParser.parse(tmpFile, mode);
                context.setPdfParseResult(pageResult);
                context.setRawText(pageResult.mergedText());
                Map<String, Object> meta = mutableMetadata(context);
                meta.put("multimodalParser", PdfPageParser.class.getSimpleName());
                meta.put("multimodalFileType", fileType.name());
                meta.put("pdfParseMode", mode.name());
                meta.put("pdfPolicyVersion", pageResult.policyVersion());
                meta.put("pdfPageCount", pageResult.pages().size());
                meta.put("pdfOcrPages", pageResult.ocrPageNumbers());
                return NodeResult.ok("PDF 按页解析完成: " + pageResult.pages().size() + " 页");
            }

            // 全限定类名避免与 core.parser.ParseResult 冲突
            com.nageoffer.ai.ragent.multimodal.parser.dto.ParseResult mmResult =
                    parser.parse(tmpFile, fileType);

            // 写回 context 原生字段
            context.setRawText(mmResult.getTextContent());

            Map<String, Object> meta = mutableMetadata(context);
            if (mmResult.getVisualDescription() != null) {
                meta.put("visualDescription", mmResult.getVisualDescription());
            }
            if (mmResult.getMetadata() != null) {
                meta.putAll(mmResult.getMetadata());
            }
            meta.put("multimodalParser", parser.getClass().getSimpleName());
            meta.put("multimodalFileType", fileType.name());

            // Phase 2: 图像类文件 → 向量化后写入 industrial_images Collection
            if (fileType.isImage()) {
                String imagePath = context.getSource() != null
                        ? context.getSource().getFileName() : "unknown";
                Map<String, Object> extraMeta = new HashMap<>();
                extraMeta.put("visualDescription", mmResult.getVisualDescription());
                imageIngestionService.ingest(
                        mmResult.getTextContent(),
                        imagePath,
                        mmResult.getSourceFile(),
                        parser.getClass().getSimpleName(),
                        extraMeta
                );
            }

        } catch (Exception e) {
            log.error("多模态解析失败: {}", context.getTaskId(), e);
            return NodeResult.fail(e);
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }

        return NodeResult.ok();
    }

    private PdfParseMode resolvePdfMode(NodeConfig config) {
        if (config == null || config.getSettings() == null) {
            return PdfParseMode.AUTO;
        }
        String configured = config.getSettings().path("pdfMode").asText("AUTO");
        try {
            return PdfParseMode.valueOf(configured.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unsupported pdfMode: " + configured, exception);
        }
    }

    private Map<String, Object> mutableMetadata(IngestionContext context) {
        Map<String, Object> existing = context.getMetadata();
        Map<String, Object> mutable = existing == null ? new HashMap<>() : new HashMap<>(existing);
        context.setMetadata(mutable);
        return mutable;
    }

    /**
     * 根据 MIME 类型或文件名后缀判断文件类型
     */
    private FileType detectType(IngestionContext context) {
        String mime = context.getMimeType();
        if (mime != null) {
            if (mime.contains("pdf")) {
                // PDF 默认走电子解析。
                // TODO Phase 2: 如果 PdfBoxParser 提取文本为空，自动 fallback 到 Tess4JParser
                return FileType.PDF_ELECTRONIC;
            }
            if (mime.contains("image")) {
                return FileType.IMAGE_PHOTO;
            }
        }

        // 从文件名后缀兜底
        String sourceName = context.getSource() != null ? context.getSource().getFileName() : null;
        if (sourceName != null) {
            String lower = sourceName.toLowerCase();
            if (lower.endsWith(".pdf")) return FileType.PDF_ELECTRONIC;
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return FileType.IMAGE_PHOTO;
        }

        return FileType.PDF_ELECTRONIC; // 默认电子文档
    }
}
