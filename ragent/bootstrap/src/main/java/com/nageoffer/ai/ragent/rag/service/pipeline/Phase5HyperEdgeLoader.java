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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.rag.core.hypergraph.EntityNode;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdge;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdgeDocumentStore;
import com.nageoffer.ai.ragent.rag.core.hypergraph.IndustrialHyperGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Phase 5 工业超边启动加载器
 * <p>
 * 将闭环 5.4/5.6 抽取并落盘的 {@code data/hypergraph/hyperedges.jsonl} 在应用启动时
 * 加载进 {@link IndustrialHyperGraph} 内存引擎，保证超图 N 元关系检索通道在正常运行
 * （非生成模式）下即有数据可用。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>默认启用（不依赖 {@code phase5.generate-hyperedges} 门控），文件存在即加载</li>
 *   <li>生成模式（{@code phase5.generate-hyperedges=true}）下跳过加载，避免与 Generator 重复写入</li>
 *   <li>幂等：仅启动时执行一次，对 {@code addHyperedges} 的重复调用天然安全（倒排索引按实体归并）</li>
 *   <li>无实体超边在 {@code addHyperedges} 内部自动跳过，无需在此重复过滤</li>
 * </ul>
 */
@Slf4j
@Component
@Order(20)
public class Phase5HyperEdgeLoader implements CommandLineRunner {

    private static final String GENERATE_PROP = "phase5.generate-hyperedges";
    private static final Path EDGE_FILE = Paths.get("data/hypergraph/hyperedges.jsonl");
    private static final Gson GSON = new Gson();

    private final IndustrialHyperGraph hyperGraph;
    private final HyperEdgeDocumentStore hyperEdgeStore;
    private final Environment env;

    public Phase5HyperEdgeLoader(IndustrialHyperGraph hyperGraph,
                                 HyperEdgeDocumentStore hyperEdgeStore,
                                 Environment env) {
        this.hyperGraph = hyperGraph;
        this.hyperEdgeStore = hyperEdgeStore;
        this.env = env;
    }

    @Override
    public void run(String... args) throws Exception {
        // 生成模式下由 Generator 负责内存加载，此处跳过避免重复
        if ("true".equals(env.getProperty(GENERATE_PROP))) {
            log.info("生成模式已启用，超边加载器跳过（由 Generator 负责内存加载）");
            return;
        }
        List<HyperEdge> persistedEdges;
        try {
            persistedEdges = hyperEdgeStore.loadActiveHyperedges();
        } catch (RuntimeException exception) {
            log.warn("Persisted hyperedge store is unavailable; falling back to JSONL", exception);
            persistedEdges = List.of();
        }
        if (!persistedEdges.isEmpty()) {
            hyperGraph.addHyperedges(persistedEdges);
            log.info("Loaded {} persisted hyperedges", persistedEdges.size());
            return;
        }
        if (!Files.isRegularFile(EDGE_FILE)) {
            log.warn("超边文件不存在，跳过加载: {}", EDGE_FILE.toAbsolutePath());
            return;
        }

        List<HyperEdge> edges = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(EDGE_FILE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                HyperEdge edge = parseEdge(line);
                if (edge != null) {
                    edges.add(edge);
                }
            }
        }

        if (edges.isEmpty()) {
            log.warn("超边文件为空或全部解析失败: {}", EDGE_FILE.toAbsolutePath());
            return;
        }

        hyperGraph.addHyperedges(edges);
        log.info("====== Phase 5: 超边加载完成，共 {} 条 ======", edges.size());
    }

    /**
     * 将 JSONL 单行解析为 {@link HyperEdge}
     * <p>
     * 与 {@link Phase5HyperEdgeGenerator#toJsonLine(HyperEdge)} 字段序列化保持对称，
     * 仅解析存在的字段，缺失字段保持 null（由 {@code allEntityValues()} 过滤）。
     */
    private HyperEdge parseEdge(String line) {
        try {
            JsonObject obj = GSON.fromJson(line, JsonObject.class);
            if (obj == null) {
                return null;
            }
            HyperEdge.HyperEdgeBuilder builder = HyperEdge.builder();
            getAsString(obj, "edgeId").ifPresent(builder::edgeId);
            getAsString(obj, "equipment").ifPresent(builder::equipment);
            getAsString(obj, "condition").ifPresent(builder::condition);
            getAsString(obj, "parameter").ifPresent(builder::parameter);
            getAsString(obj, "fault").ifPresent(builder::fault);
            getAsString(obj, "sopDoc").ifPresent(builder::sopDoc);
            getAsString(obj, "sourceDocument").ifPresent(builder::sourceDocument);

            // 扩展实体反序列化，与 Phase5HyperEdgeGenerator#toJsonLine 保持对称
            List<EntityNode> extendedEntities = parseExtendedEntities(obj);
            if (!extendedEntities.isEmpty()) {
                builder.extendedEntities(extendedEntities);
            }
            return builder.build();
        } catch (Exception e) {
            log.warn("超边 JSON 解析失败，跳过该行: {}", line, e);
            return null;
        }
    }

    private List<EntityNode> parseExtendedEntities(JsonObject obj) {
        JsonElement element = obj.get("extendedEntities");
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<EntityNode> nodes = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject node = item.getAsJsonObject();
            String label = getAsString(node, "label").orElse(null);
            String value = getAsString(node, "value").orElse(null);
            if (label != null || value != null) {
                nodes.add(new EntityNode(label, value));
            }
        }
        return nodes;
    }

    private Optional<String> getAsString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return Optional.empty();
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
