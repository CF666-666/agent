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
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描文档 OCR 解析器
 * <p>
 * 使用 Tesseract OCR 引擎提取扫描版 PDF / 图片中的中文文本。
 * 首次运行时自动从 GitHub 下载中文语言包 (~12MB)。
 * <p>
 * 线程安全：parse() 方法使用 synchronized 防止 Tesseract 实例竞争。
 */
@Slf4j
@Component
public class Tess4JParser implements MultimodalDocumentParser {

    private final Tesseract tesseract;
    private final TessDataDownloader downloader;
    private volatile boolean ocrReady = false;

    public Tess4JParser() {
        this.tesseract = new Tesseract();
        String dataPath = resolveTessDataPath().replace('\\', '/');
        Path tessDataDir = Path.of(dataPath);
        this.downloader = new TessDataDownloader(tessDataDir);

        // setDatapath 指向包含 .traineddata 文件的目录本身（统一用正斜杠，避免 JNA 路径问题）
        this.tesseract.setDatapath(dataPath);
        this.tesseract.setLanguage("chi_sim");
        this.tesseract.setOcrEngineMode(1); // LSTM 模式，中文识别更好
        log.info("Tess4J datapath: {}", dataPath);
    }

    private static String resolveTessDataPath() {
        // 优先级 1: TESSDATA_PREFIX 环境变量
        String envPath = System.getenv("TESSDATA_PREFIX");
        if (envPath != null && !envPath.isBlank()) {
            return envPath;
        }
        // 优先级 2: 工作目录下的 tessdata/
        return Path.of(System.getProperty("user.dir"), "tessdata").toString();
    }

    @PostConstruct
    public void init() {
        downloader.ensureChineseTrainedData();
        Path trainedData = Path.of(resolveTessDataPath(), "chi_sim.traineddata");
        if (trainedData.toFile().exists()) {
            ocrReady = true;
            log.info("Tess4JParser 初始化完成，OCR 就绪");
        } else {
            log.warn("Tess4JParser 初始化完成，但中文语言包未就绪，OCR 调用时将报错");
        }
    }

    @Override
    public synchronized ParseResult parse(File file, FileType fileType) {
        if (!ocrReady) {
            throw new IllegalStateException(
                    "OCR 中文语言包未就绪，请检查网络后重启。下载目标: " +
                    resolveTessDataPath() + "/chi_sim.traineddata");
        }

        try {
            String text = tesseract.doOCR(file);
            log.info("OCR 完成: {} -> {} 字符", file.getName(), text.length());

            Map<String, String> metadata = new HashMap<>();
            metadata.put("parser", "Tess4J");
            metadata.put("ocrLanguage", "chi_sim");
            metadata.put("fileName", file.getName());
            metadata.put("fileSize", String.valueOf(file.length()));

            return ParseResult.builder()
                    .sourceFile(file.getAbsolutePath())
                    .fileType(fileType)
                    .textContent(text.trim())
                    .metadata(metadata)
                    .build();
        } catch (TesseractException e) {
            log.error("OCR 失败: {}", file.getName(), e);
            throw new RuntimeException("OCR 失败: " + file.getName(), e);
        }
    }

    @Override
    public List<ParseResult> batchParse(List<File> files) {
        List<ParseResult> results = new ArrayList<>();
        for (File file : files) {
            results.add(parse(file, FileType.PDF_SCANNED));
        }
        return results;
    }

    // TODO: Phase 2+ 多页扫描 PDF → 用 PDFBox 逐页渲染为 BufferedImage，再逐页 OCR
}
