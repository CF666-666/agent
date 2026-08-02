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
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.multimodal.retrieval.image.ImageIngestionService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.framework.exception.kb.VectorCollectionAlreadyExistsException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5 全量数据入库 Runner
 * <p>
 * 通过 CommandLineRunner 在应用启动时触发，需设置系统属性 {@code phase5.ingest=true} 才会执行。
 * <p>
 * 入库逻辑：
 * <ol>
 *   <li>清空 FAQ Collection（幂等：先删再全量写入）</li>
 *   <li>读 {@code data/faq/industrial_faq.jsonl} → 逐条向量化 → 写入 Milvus</li>
 *   <li>读 {@code data/images/descriptions.jsonl} → 逐条调 {@link ImageIngestionService#ingest} 入库</li>
 * </ol>
 * 超边数据已在 {@link Phase5HyperEdgeGenerator} 中通过 {@code IndustrialHyperGraphImpl#addHyperedges} 内存加载，无需额外入库。
 */
@Slf4j
@Component
public class Phase5DataIngestionRunner implements CommandLineRunner {

    private static final String PROP_KEY = "phase5.ingest";
    private static final String FAQ_COLLECTION = "rag_default_store";
    private static final String FAQ_DOC_ID = "phase5_faq";
    private static final String IMAGE_COLLECTION = "industrial_images";
    private static final Path FAQ_FILE = Paths.get("data/faq/industrial_faq.jsonl");
    private static final Path DESC_FILE = Paths.get("data/images/descriptions.jsonl");
    private static final Gson GSON = new Gson();

    private final VectorStoreService vectorStoreService;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final EmbeddingService embeddingService;
    private final ImageIngestionService imageIngestionService;
    private final Environment env;

    public Phase5DataIngestionRunner(VectorStoreService vectorStoreService,
                                     VectorStoreAdmin vectorStoreAdmin,
                                     EmbeddingService embeddingService,
                                     ImageIngestionService imageIngestionService,
                                     Environment env) {
        this.vectorStoreService = vectorStoreService;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.embeddingService = embeddingService;
        this.imageIngestionService = imageIngestionService;
        this.env = env;
    }

    @Override
    public void run(String... args) {
        if (!"true".equals(env.getProperty(PROP_KEY))) {
            return;
        }
        log.info("====== Phase 5: 开始全量数据入库 ======");

        try {
            ingestFaqs();
        } catch (Exception e) {
            log.error("FAQ 入库失败", e);
        }

        try {
            ingestImages();
        } catch (Exception e) {
            log.error("图像入库失败", e);
        }

        log.info("====== Phase 5: 全量数据入库完成 ======");
    }

    private void ingestFaqs() throws Exception {
        if (!Files.isRegularFile(FAQ_FILE)) {
            log.warn("FAQ 文件不存在: {}", FAQ_FILE.toAbsolutePath());
            return;
        }

        // 兜底确保 Collection 存在（独立部署/重建场景下可能尚未由知识库服务创建）
        ensureVectorSpace(FAQ_COLLECTION, "Phase5 FAQ 知识库");

        // 幂等：清空再全量写入
        log.info("清空 FAQ Collection: {}", FAQ_COLLECTION);
        try {
            vectorStoreService.deleteDocumentVectors(FAQ_COLLECTION, FAQ_DOC_ID);
        } catch (Exception e) {
            log.warn("清空 FAQ Collection 失败（可能 Collection 为空）: {}", e.getMessage());
        }

        log.info("开始 FAQ 向量化入库...");
        int total = 0;
        try (BufferedReader reader = Files.newBufferedReader(FAQ_FILE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject faq = GSON.fromJson(line, JsonObject.class);
                if (faq == null || !faq.has("question") || !faq.has("answer")) {
                    log.warn("FAQ 行缺少字段，跳过: {}", line);
                    continue;
                }
                String question = faq.get("question").getAsString();
                String answer = faq.get("answer").getAsString();
                String content = question + "\n" + answer;

                List<Float> vecList = embeddingService.embed(content);
                float[] embedding = new float[vecList.size()];
                for (int i = 0; i < vecList.size(); i++) {
                    embedding[i] = vecList.get(i);
                }

                VectorChunk chunk = VectorChunk.builder()
                        .chunkId(UUID.randomUUID().toString())
                        .index(total)
                        .content(content)
                        .metadata(Map.of("source", FAQ_DOC_ID))
                        .embedding(embedding)
                        .build();

                vectorStoreService.indexDocumentChunks(FAQ_COLLECTION, FAQ_DOC_ID, List.of(chunk));
                total++;
                if (total % 50 == 0) {
                    log.info("  已入库 FAQ: {}/210", total);
                }
            }
        }
        log.info("FAQ 入库完成: {} 条", total);
    }

    private void ingestImages() throws Exception {
        if (!Files.isRegularFile(DESC_FILE)) {
            log.warn("图像描述文件不存在: {}", DESC_FILE.toAbsolutePath());
            return;
        }

        // 幂等：清空再全量写入（图像 docId 为随机 UUID，必须整 Collection 清空）
        log.info("清空图像 Collection: {}", IMAGE_COLLECTION);
        try {
            vectorStoreService.clearCollection(IMAGE_COLLECTION);
        } catch (Exception e) {
            log.warn("清空图像 Collection 失败（可能 Collection 为空）: {}", e.getMessage());
        }

        log.info("开始图像入库...");
        int total = 0;
        try (BufferedReader reader = Files.newBufferedReader(DESC_FILE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject desc = GSON.fromJson(line, JsonObject.class);
                String description = desc.get("description").getAsString();
                String imagePath = desc.get("image_path").getAsString();
                // license 未知时留空，不固化虚假授权信息
                String license = desc.has("license") ? desc.get("license").getAsString() : "";

                imageIngestionService.ingest(description, imagePath, imagePath, "Qwen-VL",
                        Map.of("license", license, "category", "industrial_equipment"));
                total++;
            }
        }
        log.info("图像入库完成: {} 张", total);
    }

    /**
     * 确保指定 Collection 存在，不存在则创建（幂等）
     * <p>
     * 通过 {@code vectorSpaceExists} 预检 + 捕获 {@code VectorCollectionAlreadyExistsException}
     * 双重防护，规避多实例并发启动时的 TOCTOU 竞态。
     */
    private void ensureVectorSpace(String collectionName, String remark) {
        VectorSpaceId spaceId = VectorSpaceId.builder()
                .logicalName(collectionName)
                .build();
        if (vectorStoreAdmin.vectorSpaceExists(spaceId)) {
            return;
        }
        try {
            vectorStoreAdmin.ensureVectorSpace(
                    VectorSpaceSpec.builder()
                            .spaceId(spaceId)
                            .remark(remark)
                            .build()
            );
            log.info("已创建 Milvus Collection: {}", collectionName);
        } catch (VectorCollectionAlreadyExistsException e) {
            log.info("Milvus Collection 已存在（并发创建被跳过）: {}", collectionName);
        }
    }
}