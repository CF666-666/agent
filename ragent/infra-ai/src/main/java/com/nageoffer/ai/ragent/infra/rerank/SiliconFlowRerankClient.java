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

package com.nageoffer.ai.ragent.infra.rerank;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** SiliconFlow's OpenAI-compatible rerank API adapter. */
@Service
@Slf4j
@RequiredArgsConstructor
public class SiliconFlowRerankClient implements RerankClient {

    @Qualifier("rerankHttpClient")
    private final OkHttpClient httpClient;

    @Override
    public String provider() {
        return ModelProvider.SILICON_FLOW.getId();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN, ModelTarget target) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> deduplicated = deduplicate(candidates);
        if (topN <= 0 || deduplicated.size() <= topN) {
            return deduplicated;
        }
        return callRemote(query, deduplicated, topN, target);
    }

    private List<RetrievedChunk> callRemote(String query, List<RetrievedChunk> candidates,
                                             int topN, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, provider());
        HttpResponseHelper.requireApiKey(provider, provider());
        JsonObject body = new JsonObject();
        body.addProperty("model", HttpResponseHelper.requireModel(target, provider()));
        body.addProperty("query", query);
        JsonArray documents = new JsonArray();
        for (RetrievedChunk candidate : candidates) {
            documents.add(candidate.getText() == null ? "" : candidate.getText());
        }
        body.add("documents", documents);
        body.addProperty("top_n", topN);
        body.addProperty("return_documents", false);

        Request request = new Request.Builder()
                .url(ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.RERANK))
                .post(RequestBody.create(body.toString(), HttpMediaTypes.JSON))
                .addHeader("Authorization", "Bearer " + provider.getApiKey())
                .build();
        JsonObject response;
        try (Response httpResponse = httpClient.newCall(request).execute()) {
            if (!httpResponse.isSuccessful()) {
                String responseBody = HttpResponseHelper.readBody(httpResponse.body());
                log.warn("{} rerank 请求失败: status={}, body={}", provider(), httpResponse.code(), responseBody);
                throw new ModelClientException(provider() + " rerank 请求失败: HTTP " + httpResponse.code(),
                        ModelClientErrorType.fromHttpStatus(httpResponse.code()), httpResponse.code());
            }
            response = HttpResponseHelper.parseJson(httpResponse.body(), provider());
        } catch (IOException exception) {
            throw new ModelClientException(provider() + " rerank 请求失败: " + exception.getMessage(),
                    ModelClientErrorType.NETWORK_ERROR, null, exception);
        }
        return applyResults(response, candidates, topN);
    }

    private List<RetrievedChunk> applyResults(JsonObject response, List<RetrievedChunk> candidates, int topN) {
        if (response == null || !response.has("results") || !response.get("results").isJsonArray()) {
            throw new ModelClientException(provider() + " rerank 响应缺少 results",
                    ModelClientErrorType.INVALID_RESPONSE, null);
        }
        List<RetrievedChunk> reranked = new ArrayList<>();
        Set<String> addedIds = new HashSet<>();
        for (JsonElement element : response.getAsJsonArray("results")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            if (!item.has("index")) {
                continue;
            }
            int index = item.get("index").getAsInt();
            if (index < 0 || index >= candidates.size()) {
                continue;
            }
            RetrievedChunk source = candidates.get(index);
            if (!addedIds.add(source.getId())) {
                continue;
            }
            reranked.add(copyWithScore(source, item));
            if (reranked.size() >= topN) {
                return reranked;
            }
        }
        for (RetrievedChunk candidate : candidates) {
            if (addedIds.add(candidate.getId())) {
                reranked.add(candidate);
            }
            if (reranked.size() >= topN) {
                break;
            }
        }
        return reranked;
    }

    private RetrievedChunk copyWithScore(RetrievedChunk source, JsonObject result) {
        if (!result.has("relevance_score") || result.get("relevance_score").isJsonNull()) {
            return source;
        }
        return RetrievedChunk.builder()
                .id(source.getId())
                .text(source.getText())
                .score(result.get("relevance_score").getAsFloat())
                .metadata(source.getMetadata() == null ? new HashMap<>() : new HashMap<>(source.getMetadata()))
                .build();
    }

    private List<RetrievedChunk> deduplicate(List<RetrievedChunk> candidates) {
        List<RetrievedChunk> result = new ArrayList<>(candidates.size());
        Set<String> ids = new HashSet<>();
        for (RetrievedChunk candidate : candidates) {
            if (ids.add(candidate.getId())) {
                result.add(candidate);
            }
        }
        return result;
    }
}
