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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工业图像语义检索通道
 * <p>
 * 将用户 query 向量化后在 {@code industrial_images} Collection 中检索，
 * 返回 Qwen-VL 生成的图像语义描述。
 * <p>
 * 与文本检索完全解耦：输入用同一个 Embedding 模型，检索用同一个 Milvus 客户端，
 * 仅通过不同 Collection 区分数据源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageSearchChannel implements SearchChannel {

    private static final String CHANNEL_NAME = "图像语义检索";
    private static final String COLLECTION_NAME = "industrial_images";
    private static final int PRIORITY = 20; // 高于文本全局通道(10)，作为图像语义补充检索
    private static final int TOP_K = 5;
    private static final String SOURCE = SearchChannelType.IMAGE_SEMANTIC.name();

    private final RetrieverService retrieverService;

    @Override
    public String getName() {
        return CHANNEL_NAME;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        // Phase 2 MVP: 不检查意图，直接启用。
        // 后续可加意图过滤：仅识别到设备/图纸类问题时启用。
        return context != null && context.getMainQuestion() != null
                && !context.getMainQuestion().isBlank();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        String query = context.getMainQuestion();
        log.info("{} 检索开始: query={}, topK={}", CHANNEL_NAME, query, TOP_K);

        RetrieveRequest request = RetrieveRequest.builder()
                .collectionName(COLLECTION_NAME)
                .query(query)
                .topK(TOP_K)
                .build();

        long start = System.currentTimeMillis();
        List<RetrievedChunk> chunks = retrieverService.retrieve(request);
        long latency = System.currentTimeMillis() - start;

        // Phase 4: 填充 source 标识到 chunk metadata
        for (RetrievedChunk chunk : chunks) {
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) {
                meta = new java.util.HashMap<>();
                chunk.setMetadata(meta);
            }
            meta.putIfAbsent("source", SOURCE);
        }

        log.info("{} 检索完成: 命中文档={}, 耗时={}ms", CHANNEL_NAME, chunks.size(), latency);
        return SearchChannelResult.builder()
                .channelType(SearchChannelType.IMAGE_SEMANTIC)
                .channelName(CHANNEL_NAME)
                .chunks(chunks)
                .latencyMs(latency)
                .build();
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.IMAGE_SEMANTIC;
    }
}
