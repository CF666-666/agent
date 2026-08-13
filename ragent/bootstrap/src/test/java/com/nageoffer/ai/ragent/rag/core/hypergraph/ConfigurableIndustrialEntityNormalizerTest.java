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

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ConfigurableIndustrialEntityNormalizerTest {

    @Test
    void shouldNormalizeConfiguredAliasesWithoutChangingUnknownEntities() {
        ConfigurableIndustrialEntityNormalizer normalizer = new ConfigurableIndustrialEntityNormalizer();
        normalizer.setAliases(Map.of("风机1号", "1号鼓风机", "一号风机", "1号鼓风机"));

        assertEquals("1号鼓风机", normalizer.normalize(" 风机1号 "));
        assertEquals("轴承过热", normalizer.normalize("轴承过热"));
        assertNull(normalizer.normalize("  "));
        assertEquals(Set.of("1号鼓风机", "轴承过热"),
                normalizer.normalizeAll(List.of("风机1号", "一号风机", "轴承过热")));
    }

    @Test
    void shouldNormalizeUnicodeCaseWhitespaceAndUnitVariantsToStableKeys() {
        ConfigurableIndustrialEntityNormalizer normalizer = new ConfigurableIndustrialEntityNormalizer();

        assertEquals("qwen3-embedding 8b", normalizer.normalize(" ＱＷＥＮ３-Embedding   8B "));
        assertEquals("10uω", normalizer.normalize("１０µΩ"));
        assertNull(normalizer.normalize("\u00a0"));
    }

    @Test
    void shouldApplyAliasesAfterSurfaceNormalization() {
        ConfigurableIndustrialEntityNormalizer normalizer = new ConfigurableIndustrialEntityNormalizer();
        normalizer.setAliases(Map.of(" 发电机定子 ", "发电机定子绕组"));

        assertEquals("发电机定子绕组", normalizer.normalize("发电机定子"));
        assertEquals(Map.of("发电机定子绕组", "发电机定子绕组", "发电机定子", "发电机定子绕组"),
                normalizer.mentionForms(List.of("发电机定子绕组")));
    }

    @Test
    void shouldBindTuningAliasesFromApplicationYaml() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(environment.getPropertySources()::addLast);
        Binder binder = Binder.get(environment);
        Map<String, String> aliases = binder.bind(
                        "ragent.hypergraph.entity-normalization.aliases",
                        Bindable.mapOf(String.class, String.class))
                .orElseThrow(() -> new AssertionError("entity normalization properties were not bound"));
        ConfigurableIndustrialEntityNormalizer normalizer = new ConfigurableIndustrialEntityNormalizer();
        normalizer.setAliases(aliases);

        assertEquals("氧化风机", normalizer.normalize("氧化风"));
        assertEquals("发电机定子绕组", normalizer.normalize("发电机定子"));
        assertEquals("浆液循环泵", normalizer.normalize("浆循泵"));
        assertEquals("脱硫塔循环泵", normalizer.normalize("脱硫循环泵"));
    }
}
