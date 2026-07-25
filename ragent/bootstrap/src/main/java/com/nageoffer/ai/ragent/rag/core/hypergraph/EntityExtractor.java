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

import com.google.gson.Gson;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工业实体抽取器
 * <p>
 * 从用户 query 中抽取工业实体（设备、故障、参数、工况、SOP 等），
 * 供 {@link IndustrialHyperGraph#matchSubgraph} 进行超图子图匹配。
 * <p>
 * 抽取策略：
 * <ol>
 *   <li>主路径：LLM Few-shot（通过 {@link LLMService} 调用，自带路由/降级/健康检查）</li>
 *   <li>降级路径：正则 + 关键词词典（LLM 全部调用失败时兜底）</li>
 * </ol>
 * <p>
 * 调用链：
 * <pre>
 *   query → EntityExtractor.extractFromQuery()
 *         → ChatRequest (system few-shot + user query, temp=0.1)
 *         → RoutingLLMService.chat() (auto routing + fallback)
 *         → JSON 数组解析 → Set&lt;String&gt;
 * </pre>
 *
 * @see IndustrialHyperGraph 超图引擎接口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EntityExtractor {

    private final LLMService llmService;

    private static final Gson GSON = new Gson();

    /**
     * Few-shot 系统提示词
     * <p>
     * 告诉 LLM 角色（工业实体抽取专家）、输出格式（纯 JSON 数组）、
     * 提供 3 个 Few-shot 示例覆盖设备/故障/参数/工况/SOP 实体类型。
     */
    private static final String SYSTEM_PROMPT = """
            你是一个工业实体抽取专家。从用户查询中提取所有工业相关实体。
            实体类型包括：设备编号/名称、故障现象、参数名称、工况描述、SOP文档编号等。
            只返回 JSON 字符串数组，不要包含任何其他内容。

            示例：
            查询："2号轧机轴承温度过高怎么办"
            返回：["2号轧机","轴承","温度过高"]

            查询："1号鼓风机冷却水流量不足导致跳闸"
            返回：["1号鼓风机","冷却水流量不足","跳闸"]

            查询："冬季润滑油粘度超标怎么处理"
            返回：["润滑油","粘度超标"]
            """;

    // ==================== 正则降级模式 ====================

    /** 设备编号模式：{@code \d+号\w+} 如 "1号鼓风机"、"2号轧机" */
    private static final Pattern DEVICE_PATTERN = Pattern.compile("\\d+号\\w+");

    /** SOP 文档编号模式：{@code SOP-\w+} 如 "SOP-2024-001" */
    private static final Pattern SOP_PATTERN = Pattern.compile("SOP-[a-zA-Z0-9]+");

    /** 常见工业故障关键词，用于正则降级时的词汇匹配 */
    private static final Set<String> FAULT_KEYWORDS = Set.of(
            "跳闸", "过热", "过载", "停机", "泄漏", "断裂",
            "振动", "噪音", "堵塞", "失效", "烧毁", "腐蚀"
    );

    // ==================== 核心方法 ====================

    /**
     * 从用户 query 中抽取工业实体
     *
     * @param query 用户原始查询文本
     * @return 去重的实体值集合，query 为空时返回空集合
     */
    public Set<String> extractFromQuery(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptySet();
        }

        try {
            ChatRequest request = ChatRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(SYSTEM_PROMPT),
                            ChatMessage.user(query)
                    ))
                    .temperature(0.1)
                    .maxTokens(256)
                    .build();

            String response = llmService.chat(request);
            return parseJsonArray(response);
        } catch (Exception e) {
            log.warn("LLM 实体抽取失败，降级为正则提取。query: {}", query, e);
            return regexFallback(query);
        }
    }

    // ==================== JSON 解析 ====================

    /**
     * 解析 LLM 返回的 JSON 字符串数组
     * <p>
     * 防御性解析：先 strip markdown 代码块标记，再截取 [] 之间的内容，
     * 最后用 Gson 反序列化为 String[]。
     *
     * @param response LLM 原始响应文本
     * @return 解析出的实体值集合
     * @throws IllegalArgumentException 响应中不包含可解析的 JSON 数组
     */
    private Set<String> parseJsonArray(String response) {
        String cleaned = response.trim()
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "");

        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end < 0 || start >= end) {
            throw new IllegalArgumentException("LLM 响应未包含 JSON 数组: " + response);
        }

        String jsonArray = cleaned.substring(start, end + 1);
        String[] entities = GSON.fromJson(jsonArray, String[].class);

        Set<String> result = new LinkedHashSet<>();
        for (String entity : entities) {
            if (entity != null && !entity.isBlank()) {
                result.add(entity.trim());
            }
        }
        return result;
    }

    // ==================== 正则降级 ====================

    /**
     * 正则 + 关键词降级抽取
     * <p>
     * 当 LLM 调用全部失败时的兜底策略。
     * 覆盖设备编号 ({@code \d+号\w+})、SOP 编号、常见故障关键词三类实体。
     *
     * @param query 用户原始查询
     * @return 正则匹配到的实体值集合
     */
    private Set<String> regexFallback(String query) {
        Set<String> entities = new LinkedHashSet<>();

        Matcher deviceMatcher = DEVICE_PATTERN.matcher(query);
        while (deviceMatcher.find()) {
            entities.add(deviceMatcher.group());
        }

        Matcher sopMatcher = SOP_PATTERN.matcher(query);
        while (sopMatcher.find()) {
            entities.add(sopMatcher.group());
        }

        for (String keyword : FAULT_KEYWORDS) {
            if (query.contains(keyword)) {
                entities.add(keyword);
            }
        }

        log.debug("正则降级抽取结果: {} → {}", query, entities);
        return entities;
    }
}
