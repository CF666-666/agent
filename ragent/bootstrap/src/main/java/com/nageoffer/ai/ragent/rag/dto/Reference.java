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

package com.nageoffer.ai.ragent.rag.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * 检索引用（SSE references 事件载荷）
 * <p>
 * 每条引用代表一个检索命中结果的结构化摘要，前端按 {@code type} 分发渲染：
 * <ul>
 *   <li>TEXT — 文本引用卡片（展示 snippet）</li>
 *   <li>IMAGE — 图片缩略图（展示 url + detail 描述）</li>
 *   <li>HYPERGRAPH — 推理路径图（展示 detail 结构化链）</li>
 * </ul>
 * <p>
 * 字段语义：
 * <ul>
 *   <li>{@code url} — 统一为真实 URL（IMAGE=图片地址，TEXT/HYPERGRAPH=null）</li>
 *   <li>{@code detail} — 补充描述（HYPERGRAPH=结构化推理路径，IMAGE=图片描述，TEXT=null）</li>
 *   <li>{@code snippet} — 文本预览片段（限 200 字符）</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Reference(
        ReferenceType type,
        String label,
        String url,
        String detail,
        String snippet,
        Map<String, Object> extra
) {}
