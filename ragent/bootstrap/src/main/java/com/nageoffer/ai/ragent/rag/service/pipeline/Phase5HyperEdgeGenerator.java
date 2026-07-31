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
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdge;
import com.nageoffer.ai.ragent.rag.core.hypergraph.IndustrialHyperGraph;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 5 工业超边抽取器
 * <p>
 * 通过 CommandLineRunner 在应用启动时触发，需设置系统属性 {@code phase5.generate-hyperedges=true} 才会执行。
 * <p>
 * 读取闭环 5.1 生成的 {@code data/faq/industrial_faq.jsonl}，逐条拼接 question + answer 作为输入文本，
 * 调用 {@link IndustrialHyperGraph#extractHyperedges(String, String)} 抽取 N 元组超边。
 * 分两步：先 extract 拿到 List → 写 JSONL + addHyperedges 内存加载。
 * <p>
 * 复用闭环 5.1 成熟模式：临时文件 + 原子重命名 + 3 次重试 + AtomicInteger 追踪数据丢失。
 * 预计产出 300-500 条超边（210 条 FAQ × 70-80% 产出率 × 2-3 条/次）。
 */
@Slf4j
@Component
public class Phase5HyperEdgeGenerator implements CommandLineRunner {

    private static final String PROP_KEY = "phase5.generate-hyperedges";
    private static final Path FAQ_FILE = Paths.get("data/faq/industrial_faq.jsonl");
    private static final Path OUTPUT_FILE = Paths.get("data/hypergraph/hyperedges.jsonl");
    private static final int MAX_RETRIES = 3;
    private static final Gson GSON = new Gson();

    private final Environment env;
    private final IndustrialHyperGraph hyperGraph;

    public Phase5HyperEdgeGenerator(Environment env, IndustrialHyperGraph hyperGraph) {
        this.env = env;
        this.hyperGraph = hyperGraph;
    }

    @Override
    public void run(String... args) {
        if (!"true".equals(env.getProperty(PROP_KEY))) {
            return;
        }
        log.info("====== Phase 5: 开始从 FAQ 抽取工业超边 ======");

        if (!Files.isRegularFile(FAQ_FILE)) {
            log.warn("FAQ 文件不存在: {}，请先运行闭环 5.1", FAQ_FILE.toAbsolutePath());
            return;
        }

        Path tmpFile = OUTPUT_FILE.resolveSibling(OUTPUT_FILE.getFileName() + ".tmp");
        try {
            Files.createDirectories(OUTPUT_FILE.getParent());
            int totalEdges = 0;
            AtomicInteger dataLossBatches = new AtomicInteger(0);

            try (BufferedReader reader = Files.newBufferedReader(FAQ_FILE);
                 BufferedWriter writer = Files.newBufferedWriter(tmpFile,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

                int totalFaqs = 0;
                int skippedFaqs = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    totalFaqs++;
                    JsonObject faq = GSON.fromJson(line, JsonObject.class);
                    String question = faq.get("question").getAsString();
                    String answer = faq.get("answer").getAsString();
                    String sourceDoc = faq.has("source_doc") ? faq.get("source_doc").getAsString() : "unknown";

                    // 拼接 question + answer 作为输入文本
                    String inputText = question + "\n" + answer;

                    // 抽取超边（带重试）
                    List<HyperEdge> edges = extractWithRetry(inputText, sourceDoc, totalFaqs, dataLossBatches);
                    if (edges.isEmpty()) {
                        skippedFaqs++;
                        continue;
                    }

                    // 写 JSONL
                    for (HyperEdge edge : edges) {
                        writer.write(toJsonLine(edge));
                        writer.newLine();
                        totalEdges++;
                    }

                    // 内存加载
                    hyperGraph.addHyperedges(edges);
                }

                log.info("  处理 FAQ: {} 条, 抽取超边: {} 条, 跳过 FAQ: {} 条",
                        totalFaqs, totalEdges, skippedFaqs);
            }

            Files.move(tmpFile, OUTPUT_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);

            log.info("====== Phase 5: 超边抽取完成，共 {} 条，丢弃批次数: {}，输出文件: {} ======",
                    totalEdges, dataLossBatches.get(), OUTPUT_FILE.toAbsolutePath());
        } catch (Exception e) {
            log.error("超边抽取失败", e);
            try {
                Files.deleteIfExists(tmpFile);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    private List<HyperEdge> extractWithRetry(String inputText, String sourceDoc, int faqIndex,
                                              AtomicInteger dataLossBatches) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                List<HyperEdge> edges = hyperGraph.extractHyperedges(inputText);
                if (edges != null && !edges.isEmpty()) {
                    return edges;
                }
                log.warn("超边抽取为空，FAQ #{} (尝试 {}/{})", faqIndex, attempt, MAX_RETRIES);
            } catch (Exception e) {
                log.warn("超边抽取失败，FAQ #{} (尝试 {}/{})", faqIndex, attempt, MAX_RETRIES, e);
            }
        }
        log.error("[DATA_LOSS] 超边永久丢弃: FAQ #{} (已重试 {} 次)", faqIndex, MAX_RETRIES);
        dataLossBatches.incrementAndGet();
        return List.of();
    }

    private String toJsonLine(HyperEdge edge) {
        JsonObject obj = new JsonObject();
        obj.addProperty("edgeId", edge.getEdgeId());
        obj.addProperty("equipment", edge.getEquipment());
        obj.addProperty("condition", edge.getCondition());
        obj.addProperty("parameter", edge.getParameter());
        obj.addProperty("fault", edge.getFault());
        obj.addProperty("sopDoc", edge.getSopDoc());
        obj.addProperty("sourceDocument", edge.getSourceDocument());
        return GSON.toJson(obj);
    }
}