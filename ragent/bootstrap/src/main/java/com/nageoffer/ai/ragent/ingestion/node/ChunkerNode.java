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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.chunk.ChunkingOptions;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategyFactory;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.core.chunk.ChunkingStrategy;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.settings.ChunkerSettings;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseResult;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 文本分块节点
 * 负责将输入的完整文本（原始文本或增强后的文本）按照指定的策略切分成多个较小的文本块（Chunk）
 */
@Component
@RequiredArgsConstructor
public class ChunkerNode implements IngestionNode {

    private final ObjectMapper objectMapper;
    private final ChunkingStrategyFactory chunkingStrategyFactory;
    private final ChunkEmbeddingService chunkEmbeddingService;

    @Override
    public String getNodeType() {
        return IngestionNodeType.CHUNKER.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if (context.getPdfParseResult() != null && StringUtils.hasText(context.getEnhancedText())) {
            return NodeResult.fail(new ClientException(
                    "PDF page-aware chunking cannot consume document-level enhancedText; "
                            + "use page-preserving enhancement or omit CONTEXT_ENHANCE"));
        }
        String text = StringUtils.hasText(context.getEnhancedText()) ? context.getEnhancedText() : context.getRawText();
        if (!StringUtils.hasText(text)) {
            return NodeResult.fail(new ClientException("可分块文本为空"));
        }
        ChunkerSettings settings = parseSettings(config.getSettings());
        ChunkingStrategy chunker = chunkingStrategyFactory.requireStrategy(settings.getStrategy());
        if (chunker == null) {
            return NodeResult.fail(new ClientException("未找到分块策略: " + settings.getStrategy()));
        }

        ChunkingOptions chunkConfig = convertToChunkConfig(settings);
        List<VectorChunk> chunks = context.getPdfParseResult() == null
                ? convertToVectorChunks(chunker.chunk(text, chunkConfig))
                : chunkPdfPages(context, chunker, chunkConfig);

        // 嵌入：为切分后的文本块生成向量
        chunkEmbeddingService.embed(chunks, null);

        context.setChunks(chunks);
        return NodeResult.ok("已分块 " + chunks.size() + " 段");
    }

    private List<VectorChunk> chunkPdfPages(
            IngestionContext context,
            ChunkingStrategy chunker,
            ChunkingOptions chunkConfig) {
        List<VectorChunk> chunks = new ArrayList<>();
        String documentKey = StringUtils.hasText(context.getIdempotencyKey())
                ? context.getIdempotencyKey()
                : context.getTaskId();
        if (!StringUtils.hasText(documentKey)) {
            documentKey = context.getPdfParseResult().sourceFile();
        }
        for (PageParseResult page : context.getPdfParseResult().pages()) {
            if (page.status() != PageParseStatus.COMPLETED) {
                continue;
            }
            for (VectorChunk local : chunker.chunk(page.text(), chunkConfig)) {
                Map<String, Object> metadata = new HashMap<>();
                if (local.getMetadata() != null) {
                    metadata.putAll(local.getMetadata());
                }
                metadata.put("pageNumber", page.pageNumber());
                metadata.put("pageStrategy", page.strategy().name());
                metadata.put("pageReason", page.reason().name());
                String stableKey = documentKey + ":" + page.pageNumber() + ":"
                        + local.getIndex() + ":" + local.getContent();
                chunks.add(VectorChunk.builder()
                        .chunkId(UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8)).toString())
                        .index(chunks.size())
                        .content(local.getContent())
                        .metadata(metadata)
                        .embedding(local.getEmbedding())
                        .build());
            }
        }
        return chunks;
    }

    private ChunkingOptions convertToChunkConfig(ChunkerSettings settings) {
        return settings.getStrategy().createDefaultOptions(
                settings.getChunkSize(), settings.getOverlapSize());
    }

    private List<VectorChunk> convertToVectorChunks(List<VectorChunk> results) {
        return results.stream()
                .map(result -> VectorChunk.builder()
                        .chunkId(result.getChunkId())
                        .index(result.getIndex())
                        .content(result.getContent())
                        .metadata(result.getMetadata())
                        .embedding(result.getEmbedding())
                        .build())
                .collect(Collectors.toList());
    }

    private ChunkerSettings parseSettings(JsonNode node) {
        ChunkerSettings settings = objectMapper.convertValue(node, ChunkerSettings.class);
        if (settings.getChunkSize() == null || settings.getChunkSize() <= 0) {
            settings.setChunkSize(512);
        }
        if (settings.getOverlapSize() == null || settings.getOverlapSize() < 0) {
            settings.setOverlapSize(128);
        }
        return settings;
    }
}
