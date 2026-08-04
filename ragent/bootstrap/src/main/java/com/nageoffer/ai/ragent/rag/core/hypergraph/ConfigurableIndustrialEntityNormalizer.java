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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Configuration-backed alias dictionary. Keys are aliases and values are the
 * canonical entity names retained by document evidence and retrieval output.
 */
@Component
@ConfigurationProperties(prefix = "ragent.hypergraph.entity-normalization")
public class ConfigurableIndustrialEntityNormalizer implements IndustrialEntityNormalizer {

    private volatile Map<String, String> aliases = Map.of();

    public void setAliases(Map<String, String> aliases) {
        Map<String, String> normalizedAliases = new LinkedHashMap<>();
        if (aliases != null) {
            aliases.forEach((alias, canonical) -> {
                if (StringUtils.hasText(alias) && StringUtils.hasText(canonical)) {
                    normalizedAliases.put(alias.trim(), canonical.trim());
                }
            });
        }
        this.aliases = Map.copyOf(normalizedAliases);
    }

    @Override
    public String normalize(String entity) {
        if (!StringUtils.hasText(entity)) {
            return null;
        }
        String trimmed = entity.trim();
        return aliases.getOrDefault(trimmed, trimmed);
    }

    @Override
    public Set<String> normalizeAll(Collection<String> entities) {
        Set<String> normalized = new LinkedHashSet<>();
        if (entities == null) {
            return normalized;
        }
        for (String entity : entities) {
            String canonical = normalize(entity);
            if (canonical != null) {
                normalized.add(canonical);
            }
        }
        return normalized;
    }
}
