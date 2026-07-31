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
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5 工业 FAQ 数据集生成器
 * <p>
 * 通过 CommandLineRunner 在应用启动时触发，需设置系统属性 {@code phase5.generate-faq=true} 才会执行。
 * <p>
 * 覆盖钢铁 / 石化 / 电力 3 个行业场景，每个场景约 80-100 条，总计 250-300 条 FAQ。
 * 输出格式为 JSONL，每行一条 JSON：{@code {"question":"...","answer":"...","category":"...","source_doc":"..."}}。
 * <p>
 * 启动方式：
 * <pre>{@code
 * java -jar bootstrap.jar --phase5.generate-faq=true
 * }</pre>
 * 或在 IDE 中设置 VM options: {@code -Dphase5.generate-faq=true}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Phase5FAQGenerator implements CommandLineRunner {

    private static final String PROP_KEY = "phase5.generate-faq";
    private static final Path OUTPUT_DIR = Paths.get("data/faq");
    private static final Path OUTPUT_FILE = OUTPUT_DIR.resolve("industrial_faq.jsonl");
    private static final Gson GSON = new Gson();

    private static final String SYSTEM_PROMPT = """
            你是一位工业知识专家，请生成一批工业设备运维 FAQ 问答对。
            
            要求：
            1. 每条 FAQ 包含 question（问题）、answer（答案）、category（分类）、source_doc（来源文档名）
            2. 问题应来自一线运维人员的真实场景，包括设备故障诊断、操作规程、安全规范、维护保养等
            3. 答案应专业、简洁（100-300字），包含具体参数（温度、压力、转速等）
            4. 每条 FAQ 独立不重复
            
            输出格式为 JSON 数组，每个元素包含 question/answer/category/source_doc 四个字段。
            一次输出 10 条。""";

    private final LLMService llmService;
    private final Environment env;

    @Override
    public void run(String... args) {
        if (!"true".equals(env.getProperty(PROP_KEY))) {
            return;
        }
        log.info("====== Phase 5: 开始生成工业 FAQ 数据集 ======");
        try {
            Files.createDirectories(OUTPUT_DIR);
            int total = 0;
            try (BufferedWriter writer = Files.newBufferedWriter(OUTPUT_FILE)) {
                total += generateForDomain(writer, "钢铁冶金", "steel_metallurgy",
                        new String[]{"高炉", "转炉", "连铸机", "热轧机", "冷轧机", "烧结机", "焦炉"});
                total += generateForDomain(writer, "石油化工", "petrochemical",
                        new String[]{"裂解炉", "精馏塔", "反应釜", "压缩机", "换热器", "泵", "储罐"});
                total += generateForDomain(writer, "电力能源", "power_energy",
                        new String[]{"汽轮机", "发电机", "锅炉", "变压器", "开关柜", "脱硫塔", "冷却塔"});
            }
            log.info("====== Phase 5: FAQ 生成完成，共 {} 条，输出文件: {} ======",
                    total, OUTPUT_FILE.toAbsolutePath());
        } catch (Exception e) {
            log.error("FAQ 生成失败", e);
        }
    }

    private int generateForDomain(BufferedWriter writer, String domain, String sourceDoc, String[] equipment) throws Exception {
        int total = 0;
        for (String equip : equipment) {
            for (int i = 0; i < 3; i++) {
                String prompt = String.format(
                        "请为 %s 行业生成 10 条关于 %s 设备运维的 FAQ 问答对。\n" +
                        "涵盖：故障诊断（3条）、操作规程（3条）、安全规范（2条）、维护保养（2条）。\n" +
                        "每条 answer 控制在 100-300 字，包含具体的技术参数。\n" +
                        "以 JSON 数组格式输出。", domain, equip);

                ChatRequest request = ChatRequest.builder()
                        .messages(List.of(
                                ChatMessage.system(SYSTEM_PROMPT),
                                ChatMessage.user(prompt)
                        ))
                        .temperature(0.7)
                        .maxTokens(4096)
                        .build();

                String response = llmService.chat(request);
                if (response == null) {
                    log.warn("LLM 返回 null，跳过 {}-{} batch {}", domain, equip, i);
                    continue;
                }

                List<String> lines = parseFAQResponse(response, sourceDoc);
                for (String line : lines) {
                    writer.write(line);
                    writer.newLine();
                    total++;
                }
                log.info("  {}-{} batch {}: 生成 {} 条", domain, equip, i, lines.size());
            }
        }
        return total;
    }

    private List<String> parseFAQResponse(String response, String sourceDoc) {
        List<String> results = new ArrayList<>();
        try {
            String trimmed = response.trim();
            if (trimmed.startsWith("```")) {
                int start = trimmed.indexOf("[");
                int end = trimmed.lastIndexOf("]");
                if (start >= 0 && end > start) {
                    trimmed = trimmed.substring(start, end + 1);
                }
            }
            JsonArray array = GSON.fromJson(trimmed, JsonArray.class);
            for (JsonElement el : array) {
                JsonObject obj = el.getAsJsonObject();
                obj.addProperty("source_doc", sourceDoc);
                results.add(GSON.toJson(obj));
            }
        } catch (Exception e) {
            log.warn("FAQ 响应解析失败: {}", response.substring(0, Math.min(100, response.length())), e);
        }
        return results;
    }
}
