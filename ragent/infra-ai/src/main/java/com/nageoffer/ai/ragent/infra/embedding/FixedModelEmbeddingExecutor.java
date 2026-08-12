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

package com.nageoffer.ai.ragent.infra.embedding;

import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Executes one embedding request against an explicitly configured model.
 *
 * <p>This executor deliberately does not use routing health state or candidate
 * fallback. It is reserved for bounded startup ingestion, where every retry
 * must reach the same remote model and therefore preserve index provenance.</p>
 */
@Component
public class FixedModelEmbeddingExecutor {

    private final AIModelProperties properties;
    private final Map<String, EmbeddingClient> clientsByProvider;

    public FixedModelEmbeddingExecutor(AIModelProperties properties, List<EmbeddingClient> clients) {
        this.properties = properties;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(EmbeddingClient::provider, Function.identity()));
    }

    public List<Float> embed(String modelId, String text) {
        ModelTarget target = resolveTarget(modelId);
        EmbeddingClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            throw new RemoteException("No embedding client is configured for provider: "
                    + target.candidate().getProvider());
        }

        List<Float> vector = client.embed(text, target);
        Integer expectedDimension = target.candidate().getDimension();
        if (vector == null || vector.isEmpty()) {
            throw new RemoteException("Embedding response is empty for model: " + modelId);
        }
        if (expectedDimension != null && vector.size() != expectedDimension) {
            throw new RemoteException("Embedding dimension mismatch for model: " + modelId
                    + ", expected=" + expectedDimension + ", actual=" + vector.size());
        }
        return vector;
    }

    private ModelTarget resolveTarget(String modelId) {
        if (!StringUtils.hasText(modelId)) {
            throw new RemoteException("Startup embedding model id must not be blank");
        }
        AIModelProperties.ModelGroup group = properties.getEmbedding();
        if (group == null || group.getCandidates() == null) {
            throw new RemoteException("No embedding candidates are configured");
        }
        AIModelProperties.ModelCandidate candidate = group.getCandidates().stream()
                .filter(item -> item != null && !Boolean.FALSE.equals(item.getEnabled()))
                .filter(item -> modelId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new RemoteException("Configured startup embedding model is unavailable: " + modelId));
        AIModelProperties.ProviderConfig provider = properties.getProviders().get(candidate.getProvider());
        if (provider == null) {
            throw new RemoteException("Provider configuration is missing: " + candidate.getProvider());
        }
        return new ModelTarget(modelId, candidate, provider);
    }
}
