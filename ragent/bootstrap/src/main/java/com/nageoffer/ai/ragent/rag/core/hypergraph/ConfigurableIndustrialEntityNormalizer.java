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

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Configuration-backed alias dictionary. Keys are aliases and values are the
 * canonical entity names retained by document evidence and retrieval output.
 */
@Component
@ConfigurationProperties(prefix = "ragent.hypergraph.entity-normalization")
public class ConfigurableIndustrialEntityNormalizer implements IndustrialEntityNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private volatile Map<String, String> aliases = Map.of();

    public void setAliases(Map<String, String> aliases) {
        Map<String, String> normalizedAliases = new LinkedHashMap<>();
        if (aliases != null) {
            aliases.forEach((alias, canonical) -> {
                if (StringUtils.hasText(alias) && StringUtils.hasText(canonical)) {
                    normalizedAliases.put(normalizeSurface(alias), normalizeSurface(canonical));
                }
            });
        }
        this.aliases = Map.copyOf(normalizedAliases);
    }

    @Override
    public String normalizeSurface(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('\u03bc', 'u')
                .replace('\u00b5', 'u');
        String surface = WHITESPACE.matcher(normalized.trim()).replaceAll(" ");
        return StringUtils.hasText(surface) ? surface : null;
    }

    @Override
    public String normalize(String entity) {
        String surface = normalizeSurface(entity);
        if (surface == null) {
            return null;
        }
        return aliases.getOrDefault(surface, surface);
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

    @Override
    public Map<String, String> mentionForms(Collection<String> canonicalEntities) {
        Set<String> canonical = normalizeAll(canonicalEntities);
        Map<String, String> forms = new LinkedHashMap<>();
        canonical.forEach(entity -> forms.put(entity, entity));
        aliases.forEach((alias, target) -> {
            if (canonical.contains(target)) {
                forms.put(alias, target);
            }
        });
        return forms;
    }
}
