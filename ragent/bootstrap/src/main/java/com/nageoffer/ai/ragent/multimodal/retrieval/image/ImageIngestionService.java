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

package com.nageoffer.ai.ragent.multimodal.retrieval.image;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 工业图像语义入库服务
 * <p>
 * 将 Qwen-VL 生成的图像描述向量化后写入 Milvus {@code industrial_images} Collection。
 * <p>
 * Schema 映射（复用现有固定 4 列 Schema）：
 * <pre>
 *   id        = UUID
 *   content   = textContent（Qwen-VL 中文描述）
 *   metadata  = {imagePath, sourceFile, visualDescription, parser, ...}
 *   embedding = embed(textContent) → float[1536]
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageIngestionService {

    private static final String COLLECTION_NAME = "industrial_images";
    private static final String COLLECTION_REMARK = "工业设备图像语义检索";

    private final VectorStoreAdmin vectorStoreAdmin;
    private final VectorStoreService vectorStoreService;
    private final EmbeddingService embeddingService;
    private final RAGDefaultProperties ragDefaultProperties;

    @PostConstruct
    public void init() {
        VectorSpaceId spaceId = VectorSpaceId.builder()
                .logicalName(COLLECTION_NAME)
                .build();

        if (!vectorStoreAdmin.vectorSpaceExists(spaceId)) {
            vectorStoreAdmin.ensureVectorSpace(
                    VectorSpaceSpec.builder()
                            .spaceId(spaceId)
                            .remark(COLLECTION_REMARK)
                            .build()
            );
            log.info("已创建 Milvus Collection: {} (维度={})",
                    COLLECTION_NAME, ragDefaultProperties.getDimension());
        } else {
            log.info("Milvus Collection 已存在: {}", COLLECTION_NAME);
        }
    }

    /**
     * 将图像描述文本向量化后写入 Milvus
     *
     * @param textContent Qwen-VL 生成的中文描述
     * @param imagePath   图像标识（文件名或路径）
     * @param sourceFile  原始文件路径
     * @param parserName  解析器名称（如 "QwenVL", "Tess4J"）
     * @param extraMeta   额外元数据（如 visualDescription）
     */
    public void ingest(String textContent,
                       String imagePath,
                       String sourceFile,
                       String parserName,
                       Map<String, Object> extraMeta) {
        if (textContent == null || textContent.isBlank()) {
            log.warn("图像描述为空，跳过入库: {}", imagePath);
            return;
        }

        // 1. Embedding
        List<Float> embeddingList = embeddingService.embed(textContent);
        float[] embedding = new float[embeddingList.size()];
        for (int i = 0; i < embeddingList.size(); i++) {
            embedding[i] = embeddingList.get(i);
        }

        // 2. 构建 metadata
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("imagePath", imagePath);
        metadata.put("sourceFile", sourceFile);
        metadata.put("parser", parserName);
        if (extraMeta != null) {
            metadata.putAll(extraMeta);
        }

        // 3. 构建 VectorChunk
        String docId = UUID.randomUUID().toString().replace("-", "");
        VectorChunk chunk = VectorChunk.builder()
                .chunkId(docId)
                .index(0)
                .content(textContent)
                .metadata(metadata)
                .embedding(embedding)
                .build();

        // 4. 写入 Milvus
        vectorStoreService.indexDocumentChunks(COLLECTION_NAME, docId,
                List.of(chunk));

        log.info("图像描述已入库: {} → collection={}, vectorDim={}, chars={}",
                imagePath, COLLECTION_NAME, embedding.length, textContent.length());
    }
}
