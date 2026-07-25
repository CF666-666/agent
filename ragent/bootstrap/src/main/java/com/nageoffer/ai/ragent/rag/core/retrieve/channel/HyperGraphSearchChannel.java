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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.hypergraph.EntityExtractor;
import com.nageoffer.ai.ragent.rag.core.hypergraph.IndustrialHyperGraph;
import com.nageoffer.ai.ragent.rag.core.hypergraph.IndustrialHyperGraph.SubgraphMatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 超图 N 元关系检索通道
 * <p>
 * 从用户 query 中抽取工业实体，通过倒排索引匹配关联超边，
 * 返回设备-工况-参数-故障的完整工业事实单元。
 * <p>
 * 检索流程：
 * <ol>
 *   <li>{@link EntityExtractor} 从 query 抽取实体集合</li>
 *   <li>{@link IndustrialHyperGraph#matchSubgraph} 倒排索引命中 → 命中数排序</li>
 *   <li>{@link IndustrialHyperGraph#expandToText} 超边 → 自然语言文本</li>
 *   <li>构造 {@link RetrievedChunk}（score = 命中数/实体总数 归一化）</li>
 * </ol>
 * <p>
 * 不同于文本/图像通道走 Milvus 向量检索，超图通道直接使用本地倒排索引
 * 做实体级精确匹配，时间复杂度 O(k)，k = query 实体数。
 * <p>
 * 通道优先级 30：低于文本(10)和图像(20)，作为工业关系推理的补充增强。
 *
 * @see IndustrialHyperGraph  超图引擎
 * @see EntityExtractor       query 实体抽取器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HyperGraphSearchChannel implements SearchChannel {

    private static final String CHANNEL_NAME = "超图N元关系检索";
    private static final int PRIORITY = 30;
    private static final int TOP_K = 10;

    private final IndustrialHyperGraph hyperGraph;
    private final EntityExtractor entityExtractor;

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
        return context != null
                && context.getMainQuestion() != null
                && !context.getMainQuestion().isBlank();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        String query = context.getMainQuestion();
        long start = System.currentTimeMillis();

        try {
            log.info("{} 检索开始: query={}, topK={}", CHANNEL_NAME, query, TOP_K);

            // Step 1: 实体抽取
            Set<String> entities = entityExtractor.extractFromQuery(query);
            log.debug("实体抽取完成: query={}, entities={}", query, entities);

            if (entities.isEmpty()) {
                long latency = System.currentTimeMillis() - start;
                log.info("{} 未抽取到实体，返回空结果。耗时={}ms", CHANNEL_NAME, latency);
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.HYPERGRAPH)
                        .channelName(CHANNEL_NAME)
                        .chunks(Collections.emptyList())
                        .latencyMs(latency)
                        .build();
            }

            // Step 2: 超图子图匹配
            List<SubgraphMatchResult> matched = hyperGraph.matchSubgraph(entities, TOP_K);
            log.debug("子图匹配完成: entityCount={}, hitCount={}", entities.size(), matched.size());

            // Step 3: 超边展开为自然语言 → RetrievedChunk
            List<RetrievedChunk> chunks = new ArrayList<>();
            float entityCount = entities.size();
            for (SubgraphMatchResult result : matched) {
                String text = hyperGraph.expandToText(result.hyperEdge());
                float score = result.matchCount() / entityCount;

                chunks.add(RetrievedChunk.builder()
                        .id(result.hyperEdge().getEdgeId())
                        .text(text)
                        .score(score)
                        .build());
            }

            long latency = System.currentTimeMillis() - start;
            log.info("{} 检索完成: 命中超边={}, 耗时={}ms", CHANNEL_NAME, chunks.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.HYPERGRAPH)
                    .channelName(CHANNEL_NAME)
                    .chunks(chunks)
                    .latencyMs(latency)
                    .build();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("{} 检索异常，返回空结果。query={}, 耗时={}ms", CHANNEL_NAME, query, latency, e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.HYPERGRAPH)
                    .channelName(CHANNEL_NAME)
                    .chunks(Collections.emptyList())
                    .latencyMs(latency)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.HYPERGRAPH;
    }
}
