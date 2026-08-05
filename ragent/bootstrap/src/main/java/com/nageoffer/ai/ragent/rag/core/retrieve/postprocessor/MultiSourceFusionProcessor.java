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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多源加权融合处理器
 * <p>
 * 在 Rerank（order=10）之前，对多路检索结果按 source 分组，
 * 实施 min-max 归一化 + 加权，确保不同检索通道在统一分数空间下公平参与精排。
 * <p>
 * 处理流程：
 * <ol>
 *   <li>按 {@code chunk.metadata["source"]} 分组；缺失 source 的 chunk 通过
 *       {@link SearchChannelResult#getChannelType()} 反查兜底</li>
 *   <li>每组内 min-max 归一化（处理 0/1/全相同 3 种边界）</li>
 *   <li>按配置权重加权</li>
 *   <li>跨组合并 + 按加权分数降序排序</li>
 * </ol>
 * <p>
 * 配置示例见 {@link FusionProperties}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiSourceFusionProcessor implements SearchResultPostProcessor {

    private static final String UNKNOWN_SOURCE = "UNKNOWN";
    private static final double DEFAULT_WEIGHT = 1.0;

    private final FusionProperties fusionProperties;

    @Override
    public String getName() {
        return "MultiSourceFusion";
    }

    @Override
    public int getOrder() {
        return 9;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        boolean enabled = context != null
                && context.getRetrievalOptions() != null
                && context.getRetrievalOptions().enableFusion()
                && fusionProperties.isEnabled();
        if (!enabled) {
            log.info("多源融合已关闭（全局配置或请求开关），跳过融合处理");
        }
        return enabled;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                         List<SearchChannelResult> results,
                                         SearchContext context) {
        if (chunks.isEmpty()) {
            return chunks;
        }

        // 预构建 chunk → channelType 映射（用于兜底补全缺失的 source）
        Map<RetrievedChunk, String> chunkToSource = buildChunkToSourceMap(results);

        // 补全 source + 按 source 分组
        Map<String, List<RetrievedChunk>> grouped = groupBySource(chunks, chunkToSource);

        // 每组归一化 + 加权
        List<RetrievedChunk> merged = new ArrayList<>();
        for (Map.Entry<String, List<RetrievedChunk>> entry : grouped.entrySet()) {
            String source = entry.getKey();
            List<RetrievedChunk> group = entry.getValue();

            double weight = fusionProperties.getWeights().getOrDefault(source, DEFAULT_WEIGHT);
            if (!fusionProperties.getWeights().containsKey(source)) {
                log.warn("source={} 未在 ragent.fusion.weights 中配置，使用默认权重 {}，建议补齐配置", source, DEFAULT_WEIGHT);
            }

            if (!group.isEmpty()) {
                normalizeAndWeight(group, weight);
            }
            merged.addAll(group);
        }

        // 按加权分数降序
        merged.sort((a, b) -> Float.compare(
                b.getScore() != null ? b.getScore() : 0f,
                a.getScore() != null ? a.getScore() : 0f));

        log.info("多源融合完成: 输入 {} 个 chunk, 来源数={}, 加权后={} 个 chunk",
                chunks.size(), grouped.size(), merged.size());
        return merged;
    }

    // ==================== 分组逻辑 ====================

    /**
     * 将 chunk 按 metadata["source"] 分组。
     * source 缺失时通过 chunk → channelType 映射兜底补全。
     */
    private Map<String, List<RetrievedChunk>> groupBySource(
            List<RetrievedChunk> chunks,
            Map<RetrievedChunk, String> chunkToSource) {

        Map<String, List<RetrievedChunk>> grouped = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            Map<String, Object> meta = chunk.getMetadata();
            String source = null;
            if (meta != null && meta.get("source") != null) {
                source = String.valueOf(meta.get("source"));
            }

            if (source == null) {
                // 兜底：通过 SearchChannelResult 反查 chunk 所属的 channelType
                source = chunkToSource.getOrDefault(chunk, UNKNOWN_SOURCE);
                if (meta != null) {
                    meta.put("source", source);
                }
            }

            grouped.computeIfAbsent(source, k -> new ArrayList<>()).add(chunk);
        }
        return grouped;
    }

    /**
     * 构建 chunk → SearchChannelType.name() 的映射
     * 用于兜底补全缺失 source 的 chunk。
     */
    private Map<RetrievedChunk, String> buildChunkToSourceMap(List<SearchChannelResult> results) {
        Map<RetrievedChunk, String> map = new IdentityHashMap<>();
        for (SearchChannelResult result : results) {
            String channelName = result.getChannelType() != null
                    ? result.getChannelType().name()
                    : UNKNOWN_SOURCE;
            for (RetrievedChunk chunk : result.getChunks()) {
                map.put(chunk, channelName);
            }
        }
        return map;
    }

    // ==================== 归一化 + 加权 ====================

    /**
     * 对一组 chunk 做 min-max 归一化后加权。
     * <p>
     * 边界处理：
     * <ul>
     *   <li>0 个 chunk → 直接返回</li>
     *   <li>1 个 chunk 或全部相同分数 → 保留原始分数仅加权（不设 1.0，防止低分放大）</li>
     *   <li>min != max → 标准 min-max 归一化</li>
     *   <li>null score → 视为 0</li>
     * </ul>
     */
    private void normalizeAndWeight(List<RetrievedChunk> group, double weight) {
        if (group.isEmpty()) {
            return;
        }

        if (group.size() == 1) {
            RetrievedChunk only = group.get(0);
            float rawScore = only.getScore() != null ? only.getScore() : 0f;
            only.setScore(rawScore * (float) weight);
            return;
        }

        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        for (RetrievedChunk c : group) {
            float s = c.getScore() != null ? c.getScore() : 0f;
            if (s < min) min = s;
            if (s > max) max = s;
        }

        if (max == min) {
            for (RetrievedChunk c : group) {
                float rawScore = c.getScore() != null ? c.getScore() : 0f;
                c.setScore(rawScore * (float) weight);
            }
            return;
        }

        float range = max - min;
        for (RetrievedChunk c : group) {
            float s = c.getScore() != null ? c.getScore() : 0f;
            float normalized = (s - min) / range;
            c.setScore(normalized * (float) weight);
        }
    }
}
