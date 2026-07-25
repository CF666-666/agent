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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 工业 N 元组超边抽取器
 * <p>
 * 从工业文档文本中通过 LLM Few-shot 抽取 N 元关系超边。
 * 长文档自动按段落分段抽取后合并结果，避免上下文窗口溢出。
 * <p>
 * 抽取策略：
 * <ol>
 *   <li>文档 ≤ 2000 字 → 直接送 LLM 单次抽取</li>
 *   <li>文档 > 2000 字 → 按段落切分 → 逐段 LLM 抽取 → 合并全部超边</li>
 *   <li>任一段抽取失败 → 跳过该段，不影响其他段</li>
 * </ol>
 * <p>
 * 调用链：
 * <pre>
 *   documentText → chunkDocument (if long)
 *                → ChatRequest (system few-shot + user chunk, temp=0.01, maxTokens=4096)
 *                → RoutingLLMService.chat() (auto routing + fallback)
 *                → JSON 数组解析 → 逐字段填充 HyperEdge → List&lt;HyperEdge&gt;
 * </pre>
 *
 * @see HyperEdge            超边数据结构
 * @see EntityNode           扩展实体节点
 * @see IndustrialHyperGraph  超图引擎接口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HyperEdgeExtractor {

    private final LLMService llmService;

    private static final Gson GSON = new Gson();

    /** 单次 LLM 调用最大输入字符数，超过则分段 */
    private static final int MAX_CHUNK_CHARS = 2000;

    /**
     * Few-shot 系统提示词
     * <p>
     * 定义角色、输出格式、字段语义，并通过 2 个示例覆盖：
     * <ul>
     *   <li>示例1：完整 5 核心字段 + 1 个扩展实体</li>
     *   <li>示例2：部分字段为 null + 3 个扩展实体</li>
     * </ul>
     */
    private static final String SYSTEM_PROMPT = """
            你是一个工业N元关系抽取专家。从以下工业文档中提取所有N元关系超边。

            每条超边包含以下字段（全部可选，无信息时设为null）：
            - equipment: 设备名称（如"1号鼓风机"、"2号轧机"）
            - condition: 工况描述（如"夏季高温40℃"）
            - parameter: 异常参数或监控指标（如"冷却水流量不足"、"轴承温度"）
            - fault: 故障现象（如"电机过载跳闸"、"温度过高"）
            - sopDoc: 关联SOP文档编号（如"SOP-2024-001"）
            - extendedEntities: 其他实体数组，格式：[{"label":"实体类型","value":"实体值"}]

            严格要求：
            1. 只返回JSON数组，不要包含任何其他文字、解释或markdown标记
            2. 无工业事实时返回空数组[]
            3. JSON必须合法：使用双引号，不要有尾随逗号
            4. 超过20条时只保留设备信息最完整的20条

            示例1：
            文档："1号鼓风机在夏季高温40℃条件下冷却水流量不足导致电机过载跳闸，按SOP-2024-001维修，维修工张三处理。"
            返回：[{"equipment":"1号鼓风机","condition":"夏季高温40℃","parameter":"冷却水流量不足","fault":"电机过载跳闸","sopDoc":"SOP-2024-001","extendedEntities":[{"label":"维修工","value":"张三"}]}]

            示例2：
            文档："2号轧机轴承温度过高，检查发现润滑油粘度超标，需更换SP-2024-002号备件。"
            返回：[{"equipment":"2号轧机","condition":null,"parameter":"轴承温度","fault":"温度过高","sopDoc":null,"extendedEntities":[{"label":"检查发现","value":"润滑油粘度超标"},{"label":"备件编号","value":"SP-2024-002"},{"label":"维修动作","value":"更换轴承"}]}]
            """;

    // ==================== 核心方法 ====================

    /**
     * 从文档文本中提取 N 元组超边
     * <p>
     * 短文档（≤{@value MAX_CHUNK_CHARS} 字）直接抽取；
     * 长文档按段落切分后逐段抽取并合并结果。
     *
     * @param documentText   文档文本
     * @param sourceDocument 来源文档路径（填充到每条超边便于溯源）
     * @return 抽取出的超边列表，无有效工业事实时返回空列表
     */
    public List<HyperEdge> extractHyperedges(String documentText, String sourceDocument) {
        if (documentText == null || documentText.isBlank()) {
            return Collections.emptyList();
        }

        if (documentText.length() <= MAX_CHUNK_CHARS) {
            return extractFromSingleChunk(documentText, sourceDocument);
        }

        return extractInChunks(documentText, sourceDocument);
    }

    // ==================== 单段抽取 ====================

    /**
     * 对单段文本进行 LLM 抽取
     */
    private List<HyperEdge> extractFromSingleChunk(String chunkText, String source) {
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(SYSTEM_PROMPT),
                        ChatMessage.user(chunkText)
                ))
                .temperature(0.01)
                .maxTokens(4096)
                .build();

        String response = llmService.chat(request);
        return parseHyperedges(response, source);
    }

    // ==================== 长文档分段 ====================

    /**
     * 长文档按段落分段 → 逐段抽取 → 合并结果
     */
    private List<HyperEdge> extractInChunks(String documentText, String sourceDocument) {
        List<String> chunks = chunkDocument(documentText);
        log.info("长文档分段抽取超边。source={}, totalChars={}, chunks={}",
                sourceDocument, documentText.length(), chunks.size());

        List<HyperEdge> allEdges = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunkSource = sourceDocument + "#chunk" + i;
            try {
                List<HyperEdge> chunkEdges = extractFromSingleChunk(chunks.get(i), chunkSource);
                if (chunkEdges != null) {
                    allEdges.addAll(chunkEdges);
                }
            } catch (Exception e) {
                log.warn("分段 {} 抽取失败，跳过。source: {}", chunkSource, e);
            }
        }
        return allEdges;
    }

    /**
     * 将长文档按段落边界切分为不超过 {@value MAX_CHUNK_CHARS} 字的片段
     * <p>
     * 切分策略：
     * <ol>
     *   <li>先按空行（\n\n）切为段落</li>
     *   <li>逐段累加到当前片段，不超限则合并</li>
     *   <li>超过限制则当前片段封存，开启新片段</li>
     * </ol>
     *
     * @param text 待切分的完整文档文本
     * @return 切分后的文本片段列表
     */
    private List<String> chunkDocument(String text) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");

        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (current.length() + trimmed.length() > MAX_CHUNK_CHARS && current.length() > 0) {
                chunks.add(current.toString());
                current = new StringBuilder();
            }

            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(trimmed);
        }

        if (current.length() > 0) {
            chunks.add(current.toString());
        }

        return chunks;
    }

    // ==================== JSON 解析 ====================

    /**
     * 解析 LLM 返回的 JSON 数组，逐字段填充 HyperEdge
     * <p>
     * 防御性解析：strip markdown → 截取 [...] → Gson 解析 → 逐字段读取。
     * 个别字段缺失或非法不影响其他字段。
     *
     * @param llmResponse    LLM 原始响应文本
     * @param sourceDocument 来源文档路径
     * @return 解析出的超边列表
     * @throws IllegalArgumentException 响应中无合法 JSON 数组
     */
    private List<HyperEdge> parseHyperedges(String llmResponse, String sourceDocument) {
        String cleaned = llmResponse.trim()
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "");

        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start < 0 || end < 0 || start >= end) {
            throw new IllegalArgumentException("LLM 响应中未找到 JSON 数组: " + llmResponse);
        }

        String jsonArray = cleaned.substring(start, end + 1);
        JsonArray array = GSON.fromJson(jsonArray, JsonArray.class);

        List<HyperEdge> edges = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();

            HyperEdge edge = HyperEdge.builder()
                    .edgeId(UUID.randomUUID().toString())
                    .equipment(getStringField(obj, "equipment"))
                    .condition(getStringField(obj, "condition"))
                    .parameter(getStringField(obj, "parameter"))
                    .fault(getStringField(obj, "fault"))
                    .sopDoc(getStringField(obj, "sopDoc"))
                    .sourceDocument(sourceDocument)
                    .extendedEntities(parseExtendedEntities(obj))
                    .build();
            edges.add(edge);
        }
        return edges;
    }

    /**
     * 安全读取 JsonObject 中的字符串字段
     * <p>
     * 使用 {@code obj.has()} + {@code JsonNull} 判断，而非 {@code obj.get() != null}，
     * 因为 Gson 的 get() 对显式 null 值返回 {@link JsonNull} 而非 Java null。
     *
     * @return 字段值（trim 后），字段不存在或为 null 时返回 null
     */
    private String getStringField(JsonObject obj, String fieldName) {
        if (!obj.has(fieldName)) {
            return null;
        }
        JsonElement field = obj.get(fieldName);
        if (field instanceof JsonNull) {
            return null;
        }
        if (!field.isJsonPrimitive()) {
            return null;
        }
        String value = field.getAsString().trim();
        return value.isEmpty() ? null : value;
    }

    // ==================== extendedEntities 解析 ====================

    /**
     * 解析扩展实体数组（三层容错）
     * <ol>
     *   <li>期望格式 — JsonArray：逐元素 [{"label":"x","value":"y"}, ...]</li>
     *   <li>兼容格式 — JsonObject：LLM 误返回 {"维修工":"张三"} → 转 entrySet</li>
     *   <li>非预期格式 — JsonPrimitive/String：跳过，log.debug</li>
     * </ol>
     *
     * @param obj 超边 JSON 对象
     * @return 解析出的 EntityNode 列表
     */
    private List<EntityNode> parseExtendedEntities(JsonObject obj) {
        if (!obj.has("extendedEntities") || obj.get("extendedEntities") instanceof JsonNull) {
            return Collections.emptyList();
        }

        JsonElement element = obj.get("extendedEntities");

        // Layer 1: 期望格式 — JsonArray
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            List<EntityNode> entities = new ArrayList<>();
            for (JsonElement item : array) {
                if (!item.isJsonObject()) {
                    continue;
                }
                JsonObject entityObj = item.getAsJsonObject();
                String label = entityObj.has("label") ? entityObj.get("label").getAsString() : null;
                String value = entityObj.has("value") ? entityObj.get("value").getAsString() : null;
                if (label != null && value != null) {
                    entities.add(new EntityNode(label.trim(), value.trim()));
                }
            }
            return entities;
        }

        // Layer 2: 兼容格式 — JsonObject（LLM 误返回 key:value 映射）
        if (element.isJsonObject()) {
            JsonObject extObj = element.getAsJsonObject();
            return extObj.entrySet().stream()
                    .map(e -> {
                        String value = e.getValue().isJsonPrimitive()
                                ? e.getValue().getAsString().trim()
                                : e.getValue().toString();
                        return new EntityNode(e.getKey().trim(), value);
                    })
                    .collect(Collectors.toList());
        }

        // Layer 3: 无法识别 — 跳过
        log.debug("extendedEntities 格式无法识别，跳过。type={}, value={}",
                element.getClass().getSimpleName(), element);
        return Collections.emptyList();
    }
}
