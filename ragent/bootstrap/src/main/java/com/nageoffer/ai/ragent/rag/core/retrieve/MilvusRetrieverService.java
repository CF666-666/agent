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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.GetLoadStateReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.BaseVector;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.type", havingValue = "milvus", matchIfMissing = true)
public class MilvusRetrieverService implements RetrieverService {

    private final EmbeddingService embeddingService;
    private final MilvusClientV2 milvusClient;
    private final RAGDefaultProperties ragDefaultProperties;

    @Override
    public List<RetrievedChunk> retrieve(RetrieveRequest retrieveParam) {
        List<Float> emb = embeddingService.embed(retrieveParam.getQuery());
        float[] vec = toArray(emb);

        float[] norm = normalize(vec);

        return retrieveByVector(norm, retrieveParam);
    }

    @Override
    public List<RetrievedChunk> retrieveByVector(float[] vector, RetrieveRequest retrieveParam) {
        String collectionName = StrUtil.isBlank(retrieveParam.getCollectionName())
                ? ragDefaultProperties.getCollectionName()
                : retrieveParam.getCollectionName();

        // Milvus 2.x 检索前必须先 load collection（异步，需等待完成）
        try {
            milvusClient.loadCollection(LoadCollectionReq.builder()
                    .collectionName(collectionName).build());
            // 等待 load 完成
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 10_000) {
                var state = milvusClient.getLoadState(
                        io.milvus.v2.service.collection.request.GetLoadStateReq.builder()
                                .collectionName(collectionName).build());
                if (Boolean.TRUE.equals(state)) break;
                Thread.sleep(500);
            }
        } catch (Exception e) {
            log.warn("loadCollection 异常: {}", e.getMessage());
        }

        List<BaseVector> vectors = List.of(new FloatVec(vector));

        Map<String, Object> params = new HashMap<>();
        params.put("metric_type", ragDefaultProperties.getMetricType());
        params.put("ef", 128);

        SearchReq req = SearchReq.builder()
                .collectionName(collectionName)
                .annsField("embedding")
                .data(vectors)
                .topK(retrieveParam.getTopK())
                .searchParams(params)
                .outputFields(List.of("id", "content", "metadata"))
                .build();

        SearchResp resp = milvusClient.search(req);
        List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

        if (results == null || results.isEmpty()) {
            return List.of();
        }

        // TODO 需确认后续是否对分数较低数据进行限制，限制多少合适？0.65？
        // TODO 如果本次查询分数都较高，是否应该扩大查询范围？1.5倍？
        return results.get(0).stream()
                .map(r -> RetrievedChunk.builder()
                        .id(Objects.toString(r.getEntity().get("id"), ""))
                        .text(Objects.toString(r.getEntity().get("content"), ""))
                        .score(r.getScore())
                        .metadata(parseMilvusMetadata(r.getEntity().get("metadata")))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 将 Milvus metadata JSON 反序列化为 Java Map
     * <p>
     * 兼容多种 Milvus SDK 返回类型：
     * <ul>
     *   <li>{@code null} → 返回空 Map（旧数据无 metadata 字段）</li>
     *   <li>{@link JsonObject}（Gson，Milvus 2.x 默认 JSON 序列化）→ 遍历 entrySet，跳过 JsonNull</li>
     *   <li>{@link Map}（部分 SDK 版本直接返回）→ 直接 putAll</li>
     *   <li>其他类型 → log.warn + 返回空 Map（防御编程）</li>
     * </ul>
     * <p>
     * 仅展开 1 层（扁平 key-value），嵌套对象/数组保留为 {@link JsonElement}，由调用方按需按类型读取。
     */
    private static Map<String, Object> parseMilvusMetadata(Object rawMetadata) {
        Map<String, Object> result = new HashMap<>();
        if (rawMetadata == null) {
            return result;
        }

        if (rawMetadata instanceof JsonObject json) {
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                JsonElement value = entry.getValue();
                if (value == null || value.isJsonNull()) {
                    continue;
                }
                result.put(entry.getKey(), toJavaValue(value));
            }
            return result;
        }

        if (rawMetadata instanceof Map<?, ?> rawMap) {
            rawMap.forEach((k, v) -> result.put(Objects.toString(k, ""), v));
            return result;
        }

        log.warn("Milvus metadata 字段类型非预期: type={}", rawMetadata.getClass().getName());
        return result;
    }

    /**
     * 将 {@link JsonElement} 转为可安全强转的 Java 原生类型：
     * <ul>
     *   <li>{@link com.google.gson.JsonPrimitive} → 按类型转为 String / Number / Boolean</li>
     *   <li>嵌套 {@link JsonObject} / {@link com.google.gson.JsonArray} → 保留为 JsonElement，由调用方按需读取</li>
     *   <li>{@code null} / {@link com.google.gson.JsonNull} → null</li>
     * </ul>
     */
    private static Object toJavaValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            return primitive.getAsString();
        }
        return element;
    }

    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private static float[] normalize(float[] v) {
        double sum = 0.0;
        for (float x : v) sum += x * x;
        double len = Math.sqrt(sum);
        float[] nv = new float[v.length];
        for (int i = 0; i < v.length; i++) nv[i] = (float) (v[i] / len);
        return nv;
    }
}
