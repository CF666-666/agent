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

package com.nageoffer.ai.ragent.framework.convention;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * RAG 检索命中结果
 * <p>
 * 表示一次向量检索或相关性搜索命中的单条记录
 * 包含原始文档片段、主键以及相关性得分
 * <p>
 * Phase 4 扩展：
 * <ul>
 *   <li>{@code metadata}：自由扩展元数据，承载来源标识（source）、附图路径（imagePath）、推理路径（hyperEdgePath）等结构化扩展字段</li>
 * </ul>
 * <p>
 * 设计原则：framework 层不依赖 bootstrap 层，因此 Chunk 来源标识不持有 SearchChannelType 枚举，
 * 改用 {@code metadata.get("source")} 字符串存储 SearchChannelType.name()。这与"展示层 ↔ 检索层解耦"原则一致。
 * <p>
 * metadata 字段约定的 key 集合（Phase 4 跨闭环协议）：
 * <ul>
 *   <li>{@code source} — SearchChannelType.name() 字符串（Channel 写入 / 融合处理器兜底）</li>
 *   <li>{@code imagePath} — 设备图纸/照片路径（ImageSearchChannel 填 / Milvus 透传）</li>
 *   <li>{@code sourceFile} — 源文档路径（入库时写 / Milvus 透传）</li>
 *   <li>{@code parser} — 解析器名称（多模态入库时写 / Milvus 透传）</li>
 *   <li>{@code hyperEdgePath} — 超边推理路径文本（HyperGraphSearchChannel 填）</li>
 *   <li>{@code matchCount} — 命中实体数（HyperGraphSearchChannel 填）</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RetrievedChunk {

    /**
     * 命中记录的唯一标识
     * 比如向量库中的 primary key 或文档 id
     */
    private String id;

    /**
     * 命中的文本内容
     * 一般是被切分后的文档片段或段落
     */
    private String text;

    /**
     * 命中得分
     * 数值越大表示与查询的相关性越高
     */
    private Float score;

    /**
     * 自由扩展元数据（Phase 4 扩展）
     * <p>
     * 承载来源标识、附图路径、推理路径等结构化扩展字段。约定 key 见类级 Javadoc。
     * <p>
     * 通过 {@code @Builder.Default} 防止 builder 模式下 metadata 字段为 null。
     * <p>
     * 来源标识读取示例：
     * <pre>{@code
     * String source = (String) chunk.getMetadata().get("source");
     * if ("IMAGE_SEMANTIC".equals(source)) { ... }
     * }</pre>
     */
    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    /**
     * 向后兼容构造器（Phase 4 过渡期使用）
     * <p>
     * 兼容老调用方的 3 参数构造，所有已知调用方已迁移至 builder 模式。新代码请使用 {@link #builder()}。
     * <p>
     * 委托给全字段构造器（{@link #RetrievedChunk(String, String, Float, Map)}），未来新增字段时只需修改一处。
     *
     * @deprecated since Phase 4 — 调用方应迁移至 {@code RetrievedChunk.builder().id(...).text(...).score(...).build()}
     */
    @Deprecated
    public RetrievedChunk(String id, String text, Float score) {
        this(id, text, score, new HashMap<>());
    }
}
