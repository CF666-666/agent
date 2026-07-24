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

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tess4J 中文语言包下载器
 * <p>
 * 首次运行时从 GitHub tesseract-ocr 仓库下载 chi_sim.traineddata (~12MB)。
 * 下载一次后永久缓存，后续启动跳过。
 * <p>
 * 包级私有，仅供 {@link Tess4JParser} 使用。
 */
@Slf4j
final class TessDataDownloader {

    private static final String TESSDATA_URL =
            "https://github.com/tesseract-ocr/tessdata/raw/main/chi_sim.traineddata";
    private static final String FILE_NAME = "chi_sim.traineddata";

    private final Path tessDataDir;

    TessDataDownloader(Path tessDataDir) {
        this.tessDataDir = tessDataDir;
    }

    /**
     * 确保中文语言包存在，不存在则下载
     */
    void ensureChineseTrainedData() {
        Path targetFile = tessDataDir.resolve(FILE_NAME);

        if (Files.exists(targetFile)) {
            log.info("中文 OCR 语言包已存在: {}", targetFile);
            return;
        }

        log.info("中文 OCR 语言包不存在，开始下载: {} -> {}", TESSDATA_URL, targetFile);
        try {
            Files.createDirectories(tessDataDir);

            long start = System.currentTimeMillis();
            try (InputStream in = new URL(TESSDATA_URL).openStream()) {
                Files.copy(in, targetFile);
            }
            long elapsed = System.currentTimeMillis() - start;

            long sizeMB = Files.size(targetFile) / (1024 * 1024);
            log.info("中文 OCR 语言包下载完成: {} MB, 耗时 {} 秒", sizeMB, elapsed / 1000);
        } catch (IOException e) {
            log.warn("中文 OCR 语言包下载失败，OCR 功能将不可用: {}", e.getMessage());
            // 清理可能不完整的文件
            try { Files.deleteIfExists(targetFile); } catch (IOException ignored) {}
        }
    }
}
