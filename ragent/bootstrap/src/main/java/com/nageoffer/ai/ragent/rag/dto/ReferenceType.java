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

/**
 * 引用类型（展示层枚举，与检索层 SearchChannelType 解耦）
 * <p>
 * 闭环 4.5 在构造 Reference 时做 6→3 映射：4 种文本通道 → TEXT，IMAGE_SEMANTIC → IMAGE，HYPERGRAPH → HYPERGRAPH。
 */
public enum ReferenceType {
    TEXT,
    IMAGE,
    HYPERGRAPH
}
