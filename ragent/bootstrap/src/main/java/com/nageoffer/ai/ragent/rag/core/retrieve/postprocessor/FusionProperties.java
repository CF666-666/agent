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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 多源融合配置属性
 * <p>
 * 支持在 application.yaml 中按 {@code ragent.fusion} 前缀配置各检索来源的权重。
 * 未显式配置的 source 默认权重 1.0（中性，不抬高不压低）。
 * <p>
 * 配置示例：
 * <pre>
 * ragent:
 *   fusion:
 *     enabled: true
 *     weights:
 *       VECTOR_GLOBAL: 1.0
 *       INTENT_DIRECTED: 1.0
 *       KEYWORD_ES: 0.9
 *       HYBRID: 1.0
 *       IMAGE_SEMANTIC: 0.9
 *       HYPERGRAPH: 1.1
 * </pre>
 */
@Data
@Component
@ConfigurationProperties("ragent.fusion")
public class FusionProperties {

    /**
     * 是否启用融合处理器
     * 默认 true（开启），设为 false 可紧急关闭融合
     */
    private boolean enabled = true;

    /**
     * source → 权重映射
     * key 为 SearchChannelType.name() 字符串，value 为 double 权重值。
     * 未配置的 source 默认权重 1.0。
     */
    private Map<String, Double> weights = new HashMap<>();
}
