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

import lombok.Builder;
import lombok.Data;

import java.util.*;
import java.util.stream.Stream;

/**
 * 工业 N 元关系超边
 * <p>
 * 表示一个完整的工业事实单元，如：
 * <pre>
 *   equipment="1号鼓风机", condition="夏季高温40℃",
 *   parameter="冷却水流量不足", fault="电机过载跳闸",
 *   sopDoc="SOP-2024-001"
 * </pre>
 * <p>
 * 设计策略（C 改良版）：
 * <ul>
 *   <li>5 个核心命名字段 → LLM Few-shot schema 约束，90% 场景覆盖</li>
 *   <li>extendedEntities → 10% 边缘实体兜底（维修工、备件编号、时间戳等）</li>
 *   <li>纯方案 A 太死（8 实体超边怎么办），纯方案 B 太散（key 漂移），纯方案 C 缺约束</li>
 * </ul>
 *
 * @see EntityNode 扩展实体节点
 * @see IndustrialHyperGraph 超图引擎接口
 */
@Data
@Builder
public class HyperEdge {

    /** 超边唯一标识，默认自动生成 UUID */
    @Builder.Default
    private String edgeId = UUID.randomUUID().toString();

    // === 5 个核心工业字段（命名锚点，LLM 抽取约束） ===

    /** 设备（如 "1号鼓风机"、"2号轧机"） */
    private String equipment;

    /** 工况（如 "夏季高温40℃"、"冬季低温-15℃"） */
    private String condition;

    /** 参数（如 "冷却水流量不足"、"润滑油粘度超标"） */
    private String parameter;

    /** 故障现象（如 "电机过载跳闸"、"轴承过热停机"） */
    private String fault;

    /** 关联 SOP 文档编号（如 "SOP-2024-001"） */
    private String sopDoc;

    // === 扩展槽 ===

    /**
     * 超出 5 核心的实体（如维修工、备件编号、时间戳）
     * <p>
     * 例如：[{"维修工", "张三"}, {"备件编号", "SP-2024-001"}, {"时间", "2024-07-15"}]
     */
    @Builder.Default
    private List<EntityNode> extendedEntities = new ArrayList<>();

    /** 来源文档路径 */
    private String sourceDocument;

    // === 工具方法 ===

    /**
     * 返回本超边包含的所有实体值，用于构建倒排索引
     * <p>
     * 包含 5 核心字段的非 null 值 + extendedEntities 中所有 EntityNode.value
     *
     * @return 去重后的实体值集合（LinkedHashSet 保序）
     */
    public Set<String> allEntityValues() {
        Set<String> values = new LinkedHashSet<>();
        Stream.of(equipment, condition, parameter, fault, sopDoc)
                .filter(Objects::nonNull)
                .forEach(values::add);
        if (extendedEntities != null) {
            extendedEntities.stream()
                    .map(EntityNode::value)
                    .filter(Objects::nonNull)
                    .forEach(values::add);
        }
        return values;
    }
}
