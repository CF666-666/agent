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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.multimodal.parser.MultimodalDocumentParser;
import com.nageoffer.ai.ragent.multimodal.parser.dto.FileType;
import com.nageoffer.ai.ragent.multimodal.parser.dto.ParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 5 工业图像描述生成器
 * <p>
 * 通过 CommandLineRunner 在应用启动时触发，需设置系统属性 {@code phase5.generate-descriptions=true} 才会执行。
 * <p>
 * 扫描 {@code data/images/drawings/} 目录下的所有图片文件，调用 Qwen-VL 生成工业设备描述，输出 JSONL：
 * <pre>{@code
 * {"image_path":"drawings/steel_blast_furnace_001.jpg","description":"...","category":"industrial_equipment","source_url":"...","license":"CC BY-SA 4.0","generated_by":"qwen-vl-max"}
 * }</pre>
 * <p>
 * 复用闭环 5.1 成熟模式：临时文件 + 原子重命名 + 3 次重试 + AtomicInteger 追踪数据丢失。
 */
@Slf4j
@Component
public class Phase5ImageDescriber implements CommandLineRunner {

    private static final String PROP_KEY = "phase5.generate-descriptions";
    private static final Path INPUT_DIR = Paths.get("data/images/drawings");
    private static final Path OUTPUT_FILE = Paths.get("data/images/descriptions.jsonl");
    private static final int MAX_RETRIES = 3;
    private static final Gson GSON = new Gson();

    /** 可选的图片元数据文件（filename → {url, license}），用户手动维护 */
    private static final Path METADATA_FILE = OUTPUT_FILE.resolveSibling("image_metadata.jsonl");

    private final Environment env;
    private final MultimodalDocumentParser imageParser;

    public Phase5ImageDescriber(Environment env,
                                @Qualifier("qwenVLImageParser") MultimodalDocumentParser imageParser) {
        this.env = env;
        this.imageParser = imageParser;
    }

    @Override
    public void run(String... args) {
        if (!"true".equals(env.getProperty(PROP_KEY))) {
            return;
        }
        log.info("====== Phase 5: 开始生成工业图像描述 ======");

        if (!Files.isDirectory(INPUT_DIR)) {
            log.warn("图像目录不存在: {}，跳过", INPUT_DIR.toAbsolutePath());
            return;
        }

        Path tmpFile = OUTPUT_FILE.resolveSibling(OUTPUT_FILE.getFileName() + ".tmp");
        try {
            Files.createDirectories(OUTPUT_FILE.getParent());
            int total = 0;
            AtomicInteger dataLossBatches = new AtomicInteger(0);

            try (BufferedWriter writer = Files.newBufferedWriter(tmpFile,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                int[] counts = scanAndDescribe(writer, dataLossBatches);
                total = counts[0];
            }

            Files.move(tmpFile, OUTPUT_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            log.info("====== Phase 5: 图像描述生成完成，共 {} 条，丢弃批次数: {}，输出文件: {} ======",
                    total, dataLossBatches.get(), OUTPUT_FILE.toAbsolutePath());
        } catch (Exception e) {
            log.error("图像描述生成失败", e);
            try {
                Files.deleteIfExists(tmpFile);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    private int[] scanAndDescribe(BufferedWriter writer, AtomicInteger dataLossBatches) throws Exception {
        var imageFiles = Files.list(INPUT_DIR)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String name = p.getFileName().toString().toLowerCase();
                    return name.endsWith(".jpg") || name.endsWith(".jpeg")
                            || name.endsWith(".png") || name.endsWith(".webp");
                })
                .sorted()
                .toList();
        int totalBatches = imageFiles.size();
        if (totalBatches == 0) {
            log.warn("图像目录为空: {}，无图片可处理", INPUT_DIR.toAbsolutePath());
            return new int[]{0};
        }

        // 读取可选元数据文件（filename → {url, license}），缺失时回退空串
        Map<String, ImageMetadata> metadataMap = loadImageMetadata();

        log.info("发现 {} 张待描述图像", totalBatches);

        int total = 0;
        int currentBatch = 0;
        for (Path imageFile : imageFiles) {
            currentBatch++;
            log.info("  [{}/{}] 处理 {}", currentBatch, totalBatches, imageFile.getFileName());

            String description = callQwenVLWithRetry(imageFile, dataLossBatches);
            if (description == null) {
                continue;
            }

            String relPath = "drawings/" + imageFile.getFileName();
            ImageMetadata meta = metadataMap.getOrDefault(relPath, ImageMetadata.EMPTY);

            JsonObject out = new JsonObject();
            out.addProperty("image_path", relPath);
            out.addProperty("description", description);
            out.addProperty("category", "industrial_equipment");
            out.addProperty("source_url", meta.sourceUrl());
            out.addProperty("license", meta.license());
            out.addProperty("generated_by", "qwen-vl-max");

            writer.write(GSON.toJson(out));
            writer.newLine();
            total++;
        }

        return new int[]{total};
    }

    /**
     * 读取 {@code image_metadata.jsonl}（filename → {source_url, license}）
     * <p>
     * 元数据文件为可选项：不存在或某图缺失时回退为空串，
     * 不固化虚假授权信息（license 未知即留空）。
     */
    private Map<String, ImageMetadata> loadImageMetadata() {
        Map<String, ImageMetadata> result = new HashMap<>();
        if (!Files.isRegularFile(METADATA_FILE)) {
            log.info("图像元数据文件不存在，source_url/license 将留空: {}", METADATA_FILE.toAbsolutePath());
            return result;
        }
        try (BufferedReader reader = Files.newBufferedReader(METADATA_FILE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject obj = GSON.fromJson(line, JsonObject.class);
                if (obj == null || !obj.has("image_path")) {
                    continue;
                }
                String path = obj.get("image_path").getAsString();
                String url = getAsString(obj, "source_url");
                String license = getAsString(obj, "license");
                result.put(path, new ImageMetadata(url, license));
            }
        } catch (Exception e) {
            log.warn("图像元数据解析失败，source_url/license 将留空: {}", METADATA_FILE.toAbsolutePath(), e);
        }
        return result;
    }

    private static String getAsString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return "";
        }
        return element.getAsString();
    }

    /**
     * 图像来源元数据
     *
     * @param sourceUrl 图片来源 URL（未知为空串）
     * @param license   授权信息（未知为空串）
     */
    private record ImageMetadata(String sourceUrl, String license) {

        static final ImageMetadata EMPTY = new ImageMetadata("", "");
    }

    private String callQwenVLWithRetry(Path imageFile, AtomicInteger dataLossBatches) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ParseResult result = imageParser.parse(imageFile.toFile(), FileType.IMAGE_DRAWING);
                String description = result != null ? result.getTextContent() : null;
                if (description != null && !description.isBlank()) {
                    return description;
                }
                log.warn("Qwen-VL 返回空描述，{} (尝试 {}/{})",
                        imageFile.getFileName(), attempt, MAX_RETRIES);
            } catch (Exception e) {
                log.warn("Qwen-VL 调用失败，{} (尝试 {}/{})",
                        imageFile.getFileName(), attempt, MAX_RETRIES, e);
            }
        }
        log.error("[DATA_LOSS] 图像描述永久丢弃: {} (已重试 {} 次)",
                imageFile.getFileName(), MAX_RETRIES);
        dataLossBatches.incrementAndGet();
        return null;
    }
}