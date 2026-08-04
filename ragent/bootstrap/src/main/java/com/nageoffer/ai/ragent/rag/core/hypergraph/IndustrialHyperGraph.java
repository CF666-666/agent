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

package com.nageoffer.ai.ragent.rag.core.hypergraph;

import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * 工业超图引擎接口
 * <p>
 * 核心能力：
 * <ol>
 *   <li>从文档文本中抽取 N 元组超边（extractHyperedges）</li>
 *   <li>将超边加入倒排索引（addHyperedges）</li>
 *   <li>根据 query 实体做子图匹配，按命中数降序（matchSubgraph）</li>
 *   <li>将超边展开为自然语言文本，供 Embedding 向量化 + LLM 推理（expandToText）</li>
 * </ol>
 * <p>
 * 设计决策：
 * <ul>
 *   <li>倒排索引结构：{@code Map<实体值, Set<超边下标>>}，按 entity value 而非 field name 建索引</li>
 *   <li>matchSubgraph 返回带命中计数的 SubgraphMatchResult，便于闭环 3 排序</li>
 *   <li>expandToText 使用模板 default method，0 API 消耗，实现类可覆盖</li>
 * </ul>
 *
 * @see HyperEdge  超边数据结构
 * @see EntityNode 扩展实体节点
 */
public interface IndustrialHyperGraph {

    /**
     * 从文档文本中抽取 N 元组超边
     * <p>
     * 实现策略：LLM Few-shot → 结构化输出（5 核心 + 扩展实体）
     * 示例 Prompt（闭环 3 细化）：
     * <pre>
     *   从以下工业文本中抽取 N 元关系超边，每条超边包含：
     *   - equipment: 设备名称
     *   - condition: 工况描述
     *   - parameter: 异常参数
     *   - fault: 故障现象
     *   - sopDoc: 关联 SOP 文档编号
     *   - extendedEntities: 其他实体（如维修人员、备件编号、时间）
     * </pre>
     *
     * @param documentText 文档文本
     * @return 抽取出的超边列表
     */
    List<HyperEdge> extractHyperedges(String documentText);

    /**
     * 从文档文本中抽取 N 元组超边，并填充来源文档路径
     * <p>
     * 用于数据生成流水线：将 FAQ 的 sourceDoc 透传给每条超边的 {@code sourceDocument} 字段，
     * 保证数据链路末端（超边 → 来源 FAQ）溯源不中断。
     *
     * @param documentText   文档文本
     * @param sourceDocument 来源文档标识（如 FAQ 的 source_doc）
     * @return 抽取出的超边列表
     */
    List<HyperEdge> extractHyperedges(String documentText, String sourceDocument);

    /**
     * 将超边加入倒排索引
     * <p>
     * 构建 {@code Map<实体值, Set<超边下标>>} 倒排索引，
     * 其中 key 是实体值（如 "1号鼓风机"），value 是该实体出现的所有超边下标。
     * <p>
     * 构建逻辑：
     * <pre>
     * for (int i = 0; i &lt; edges.size(); i++) {
     *     for (String entity : edges.get(i).allEntityValues()) {
     *         entityToEdgeIdx.computeIfAbsent(entity, k -&gt; new HashSet&lt;&gt;()).add(i);
     *     }
     * }
     * </pre>
     * <p>
     * 可多次调用，增量添加超边。
     *
     * @param edges 待索引的超边列表
     */
    void addHyperedges(List<HyperEdge> edges);

    /**
     * Atomically replaces every in-memory edge for one source document.
     * Re-ingestion must use this operation instead of {@link #addHyperedges(List)}
     * so stale document versions no longer participate in retrieval.
     */
    void replaceDocumentHyperedges(String sourceDocument, List<HyperEdge> edges);

    /**
     * 根据 query 实体做子图匹配
     * <p>
     * 匹配逻辑：
     * <ol>
     *   <li>倒排索引检索：query 中任意实体命中 → 候选超边集合</li>
     *   <li>按命中实体数降序排列</li>
     *   <li>返回 Top-N 结果，每条结果包含超边 + 命中计数</li>
     * </ol>
     * <p>
     * 示例：
     * <pre>
     *   query: "1号鼓风机为什么跳闸"
     *   → entities: {"1号鼓风机", "跳闸"}
     *   → "1号鼓风机" 命中 [edge_0, edge_3, edge_7]
     *   → "跳闸"     命中 [edge_0, edge_5]
     *   → edge_0(2命中) &gt; edge_3(1命中) &gt; edge_5(1命中) &gt; edge_7(1命中)
     * </pre>
     *
     * @param queryEntities 从用户 query 中抽取的实体集合
     * @param maxEdges      最大返回超边数
     * @return 匹配结果列表，按命中数降序
     */
    List<SubgraphMatchResult> matchSubgraph(Set<String> queryEntities, int maxEdges);

    /**
     * 将超边展开为自然语言文本（模板方法，0 API 消耗）
     * <p>
     * 用途：
     * <ul>
     *   <li>Embedding 向量化（HyperGraphSearchChannel 调 embeddingService.embed(text)）</li>
     *   <li>LLM 答案生成（作为 context 拼接进 Prompt）</li>
     * </ul>
     * <p>
     * 设计理由（模板 vs LLM 展开）：
     * <ul>
     *   <li>结构化事实对 Qwen3-Embedding-8B 语义覆盖完全够用</li>
     *   <li>LLM 自己会推理，给结构化事实比给散文更高效</li>
     *   <li>模板 Token 省一半，0 API 消耗</li>
     * </ul>
     * <p>
     * 实现类可以覆盖此方法以定制展开策略。
     *
     * @param edge 待展开的超边
     * @return 自然语言文本
     */
    default String expandToText(HyperEdge edge) {
        StringJoiner sj = new StringJoiner("，");
        if (edge.getEquipment() != null) {
            sj.add(edge.getEquipment());
        }
        if (edge.getCondition() != null) {
            sj.add("在" + edge.getCondition() + "条件下");
        }
        if (edge.getParameter() != null) {
            sj.add("因" + edge.getParameter() + "异常");
        }
        if (edge.getFault() != null) {
            sj.add("导致" + edge.getFault());
        }
        if (edge.getSopDoc() != null) {
            sj.add("参考" + edge.getSopDoc());
        }
        if (edge.getExtendedEntities() != null) {
            edge.getExtendedEntities().forEach(e ->
                    sj.add(e.label() + ":" + e.value())
            );
        }
        return sj.toString();
    }

    /**
     * 子图匹配结果
     *
     * @param hyperEdge  命中的超边
     * @param matchCount query 中有几个实体命中了此超边
     */
    record SubgraphMatchResult(HyperEdge hyperEdge, int matchCount) {
    }
}
