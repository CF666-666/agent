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

package com.nageoffer.ai.ragent.multimodal.parser;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.multimodal.parser.dto.FileType;
import com.nageoffer.ai.ragent.multimodal.parser.dto.ParseResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Qwen-VL 图像语义描述解析器
 * <p>
 * 调用百炼 DashScope Qwen-VL 多模态 API，将设备图纸/现场照片转换为中文语义描述。
 * 不走 ChatClient 继承体系（Qwen-VL 使用独立的 DashScope 多模态端点）。
 * <p>
 * 工业引导 Prompt 帮助 Qwen-VL 关注设备型号、部件关系、仪表读数等工业关键信息。
 */
@Slf4j
@Component
public class QwenVLImageParser implements MultimodalDocumentParser {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // TODO Phase 2: 收拢到 ai.providers.bailian.endpoints.multimodal 配置项
    private static final String API_PATH = "/api/v1/services/aigc/multimodal-generation/generation";

    private static final String MODEL_NAME = "qwen-vl-max";

    private static final String INDUSTRIAL_PROMPT =
            "你是一个工业设备分析专家。请用中文详细描述这张图片中的内容，" +
            "重点关注以下信息：\n" +
            "1. 设备类型和型号\n" +
            "2. 关键部件及其空间关系\n" +
            "3. 仪表读数、铭牌文字、操作标识\n" +
            "4. 异常或故障迹象（如有）\n\n" +
            "请控制在200字以内，仅输出描述内容，不要加前缀说明。";

    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024; // 10MB

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final String apiUrl;
    private final String apiKey;

    public QwenVLImageParser(
            @Qualifier("syncHttpClient") OkHttpClient syncHttpClient,
            @Value("${ai.providers.bailian.url}") String bailianUrl,
            @Value("${ai.providers.bailian.api-key}") String apiKey) {
        this.httpClient = syncHttpClient != null
                ? syncHttpClient.newBuilder().readTimeout(45, TimeUnit.SECONDS).build()
                : null;
        this.gson = new Gson();
        this.apiUrl = bailianUrl != null ? bailianUrl + API_PATH : null;
        this.apiKey = apiKey;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("BAILIAN_API_KEY 未设置，Qwen-VL 调用将失败 (401)");
        }
    }

    @Override
    public ParseResult parse(File file, FileType fileType) {
        validateFileType(fileType);
        validateImageFile(file);

        String base64Image = encodeToBase64(file);
        String requestBody = buildRequest(base64Image);

        log.info("调用 Qwen-VL: {} ({} KB)", file.getName(), file.length() / 1024);
        String description = callQwenVL(requestBody);

        return ParseResult.builder()
                .sourceFile(file.getAbsolutePath())
                .fileType(fileType)
                .textContent(description)        // 供 Embedding / 切 chunk
                .visualDescription(description)  // 标记来源
                .metadata(Map.of(
                        "parser", "Qwen-VL",
                        "model", MODEL_NAME,
                        "fileName", file.getName()
                ))
                .build();
    }

    @Override
    public List<ParseResult> batchParse(List<File> files) {
        List<ParseResult> results = new ArrayList<>();
        for (File file : files) {
            FileType type = detectType(file.getName());
            results.add(parse(file, type));
        }
        return results;
    }

    // ==================== private methods ====================

    private void validateFileType(FileType fileType) {
        if (fileType != FileType.IMAGE_DRAWING && fileType != FileType.IMAGE_PHOTO) {
            throw new IllegalArgumentException(
                    "QwenVLImageParser 仅支持 IMAGE_DRAWING / IMAGE_PHOTO，实际: " + fileType);
        }
    }

    private void validateImageFile(File file) {
        if (file.length() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(
                    "图片过大: " + file.length() / 1024 / 1024 + "MB，上限 10MB");
        }
    }

    private String encodeToBase64(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            return Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new RuntimeException("读取图片失败: " + file.getName(), e);
        }
    }

    private String buildRequest(String base64Image) {
        // DashScope Qwen-VL 请求格式（与 Phase 0 B9 测试一致）
        JsonObject request = new JsonObject();
        request.addProperty("model", MODEL_NAME);

        JsonObject input = new JsonObject();
        JsonArray messages = new JsonArray();

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");

        JsonArray content = new JsonArray();
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("image", "data:image/jpeg;base64," + base64Image);
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", INDUSTRIAL_PROMPT);

        content.add(imagePart);
        content.add(textPart);
        message.add("content", content);
        messages.add(message);
        input.add("messages", messages);
        request.add("input", input);

        return gson.toJson(request);
    }

    private String callQwenVL(String requestBody) {
        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(requestBody, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Qwen-VL API 返回错误 {}: {}", response.code(), body);
                throw new RuntimeException("Qwen-VL API 调用失败 " + response.code());
            }
            return extractText(body);
        } catch (IOException e) {
            log.error("Qwen-VL API 网络异常", e);
            throw new RuntimeException("Qwen-VL API 网络异常", e);
        }
    }

    /**
     * 从 DashScope 响应中提取文本
     * <p>
     * 响应格式: output.choices[0].message.content = [{"text": "..."}, ...]
     * content 是数组，需要遍历拼接。
     */
    private String extractText(String responseBody) {
        JsonObject root = gson.fromJson(responseBody, JsonObject.class);

        // 防御：DashScope 可能返回异常 JSON（如 {"code":"InvalidParameter","message":"..."}）
        if (!root.has("output") || root.get("output").isJsonNull()) {
            throw new RuntimeException("Qwen-VL 响应缺少 output 字段: " + responseBody);
        }

        JsonArray choices = root.getAsJsonObject("output").getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("Qwen-VL 响应 choices 为空: " + responseBody);
        }

        JsonObject messageObj = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (messageObj == null || !messageObj.has("content")) {
            throw new RuntimeException("Qwen-VL 响应缺少 message.content: " + responseBody);
        }

        JsonArray contentArray = messageObj.getAsJsonArray("content");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < contentArray.size(); i++) {
            JsonObject part = contentArray.get(i).getAsJsonObject();
            if (part.has("text")) {
                sb.append(part.get("text").getAsString());
            }
        }
        String text = sb.toString().trim();
        log.info("Qwen-VL 描述: {} 字符", text.length());
        return text;
    }

    private FileType detectType(String fileName) {
        return FileType.IMAGE_PHOTO; // 默认照片类型，调用方指定
    }
}
