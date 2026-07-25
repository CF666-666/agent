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

/**
 * 超边中的实体节点
 * <p>
 * 轻量级标签-值对，用于记录 LLM 抽取的工业实体。
 * 核心 5 字段（设备/工况/参数/故障/SOP）不在 EntityNode 中，
 * 而是作为 {@link HyperEdge} 的命名属性，确保 Few-shot schema 约束强度。
 * <p>
 * 示例：
 * <pre>
 *   new EntityNode("维修工", "张三")
 *   new EntityNode("备件编号", "SP-2024-001")
 *   new EntityNode("时间", "2024-07-15")
 * </pre>
 *
 * @param label 实体类型标签（如"维修工"、"备件编号"）
 * @param value 实体值（如"张三"、"SP-2024-001"）
 */
public record EntityNode(String label, String value) {
}
