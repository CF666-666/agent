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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.http.HttpMediaTypes;
import com.nageoffer.ai.ragent.infra.http.HttpResponseHelper;
import com.nageoffer.ai.ragent.infra.http.ModelUrlResolver;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.multimodal.parser.dto.FileType;
import com.nageoffer.ai.ragent.multimodal.parser.dto.ParseResult;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 工业图纸/现场照片语义解析器。
 *
 * <p>模型供应商与模型名称由 {@code ai.vision} 候选配置决定。SiliconFlow 使用
 * OpenAI 兼容多模态协议，百炼保留 DashScope 原生多模态协议；两者均通过同一模型
 * 路由执行器进行健康检查与故障切换。</p>
 */
@Slf4j
@Component
public class QwenVLImageParser implements MultimodalDocumentParser {

    private static final Gson GSON = new Gson();
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final String INDUSTRIAL_PROMPT = """
            你是一名工业设备分析专家。请用中文详细描述图片内容，重点关注：
            1. 设备类型和型号
            2. 关键部件及其空间关系
            3. 仪表读数、铭牌文字、操作标识
            4. 异常或故障迹象（如有）

            请控制在 200 字以内，仅输出描述内容，不要添加前缀说明。
            """;

    private final OkHttpClient httpClient;
    private final ModelSelector modelSelector;
    private final ModelRoutingExecutor modelRoutingExecutor;

    public QwenVLImageParser(
            @Qualifier("syncHttpClient") OkHttpClient syncHttpClient,
            ModelSelector modelSelector,
            ModelRoutingExecutor modelRoutingExecutor) {
        this.httpClient = syncHttpClient.newBuilder().readTimeout(45, TimeUnit.SECONDS).build();
        this.modelSelector = modelSelector;
        this.modelRoutingExecutor = modelRoutingExecutor;
    }

    @Override
    public ParseResult parse(File file, FileType fileType) {
        validateFileType(fileType);
        validateImageFile(file);
        String base64Image = encodeToBase64(file);

        log.info("调用视觉模型解析图片: {} ({} KB)", file.getName(), file.length() / 1024);
        VisionResponse vision = modelRoutingExecutor.executeWithFallback(
                ModelCapability.MULTIMODAL,
                modelSelector.selectVisionCandidates(),
                target -> target,
                (ignored, target) -> callVisionModel(base64Image, target)
        );

        return ParseResult.builder()
                .sourceFile(file.getAbsolutePath())
                .fileType(fileType)
                .textContent(vision.description())
                .visualDescription(vision.description())
                .metadata(Map.of(
                        "parser", "Qwen-VL",
                        "provider", vision.target().candidate().getProvider(),
                        "model", vision.target().candidate().getModel(),
                        "fileName", file.getName()
                ))
                .build();
    }

    @Override
    public List<ParseResult> batchParse(List<File> files) {
        List<ParseResult> results = new ArrayList<>();
        for (File file : files) {
            results.add(parse(file, detectType(file.getName())));
        }
        return results;
    }

    private VisionResponse callVisionModel(String base64Image, ModelTarget target) {
        AIModelProperties.ProviderConfig provider = HttpResponseHelper.requireProvider(target, "vision");
        HttpResponseHelper.requireApiKey(provider, target.candidate().getProvider());
        String endpoint = ModelUrlResolver.resolveUrl(provider, target.candidate(), ModelCapability.MULTIMODAL);
        String requestBody = buildRequest(base64Image, target);
        Request request = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + provider.getApiKey())
                .post(RequestBody.create(requestBody, HttpMediaTypes.JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = HttpResponseHelper.readBody(response.body());
            if (!response.isSuccessful()) {
                throw new IllegalStateException("Vision model request failed: provider="
                        + target.candidate().getProvider() + ", status=" + response.code());
            }
            return new VisionResponse(extractText(responseBody, target.candidate().getProvider()), target);
        } catch (IOException e) {
            throw new IllegalStateException("Vision model network request failed", e);
        }
    }

    static String buildRequest(String base64Image, ModelTarget target) {
        if (ModelProvider.SILICON_FLOW.matches(target.candidate().getProvider())) {
            return buildOpenAiRequest(base64Image, target);
        }
        if (ModelProvider.BAI_LIAN.matches(target.candidate().getProvider())) {
            return buildDashScopeRequest(base64Image, target);
        }
        throw new IllegalArgumentException("Unsupported vision provider: " + target.candidate().getProvider());
    }

    static String extractText(String responseBody, String provider) {
        JsonObject root = GSON.fromJson(responseBody, JsonObject.class);
        if (ModelProvider.SILICON_FLOW.matches(provider)) {
            return extractOpenAiText(root, responseBody);
        }
        if (ModelProvider.BAI_LIAN.matches(provider)) {
            return extractDashScopeText(root, responseBody);
        }
        throw new IllegalArgumentException("Unsupported vision provider: " + provider);
    }

    private static String buildOpenAiRequest(String base64Image, ModelTarget target) {
        JsonObject request = new JsonObject();
        request.addProperty("model", HttpResponseHelper.requireModel(target, "vision"));
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", "data:image/jpeg;base64," + base64Image);
        imagePart.add("image_url", imageUrl);
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", INDUSTRIAL_PROMPT);
        content.add(imagePart);
        content.add(textPart);
        message.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(message);
        request.add("messages", messages);
        return GSON.toJson(request);
    }

    private static String buildDashScopeRequest(String base64Image, ModelTarget target) {
        JsonObject request = new JsonObject();
        request.addProperty("model", HttpResponseHelper.requireModel(target, "vision"));
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
        return GSON.toJson(request);
    }

    private static String extractOpenAiText(JsonObject root, String responseBody) {
        if (root == null || !root.has("choices") || !root.get("choices").isJsonArray()) {
            throw new IllegalStateException("Vision response is missing choices: " + responseBody);
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
            throw new IllegalStateException("Vision response choices is empty: " + responseBody);
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        return requireText(message == null ? null : message.get("content"), responseBody);
    }

    private static String extractDashScopeText(JsonObject root, String responseBody) {
        if (root == null || !root.has("output") || !root.get("output").isJsonObject()) {
            throw new IllegalStateException("Vision response is missing output: " + responseBody);
        }
        JsonObject output = root.getAsJsonObject("output");
        JsonArray choices = output.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            throw new IllegalStateException("Vision response choices is empty: " + responseBody);
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null || !message.has("content") || !message.get("content").isJsonArray()) {
            throw new IllegalStateException("Vision response is missing message.content: " + responseBody);
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement element : message.getAsJsonArray("content")) {
            if (element.isJsonObject() && element.getAsJsonObject().has("text")) {
                text.append(element.getAsJsonObject().get("text").getAsString());
            }
        }
        return requireText(text.toString(), responseBody);
    }

    private static String requireText(JsonElement element, String responseBody) {
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            throw new IllegalStateException("Vision response is missing textual content: " + responseBody);
        }
        return requireText(element.getAsString(), responseBody);
    }

    private static String requireText(String text, String responseBody) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Vision response content is empty: " + responseBody);
        }
        return text.trim();
    }

    private void validateFileType(FileType fileType) {
        if (fileType != FileType.IMAGE_DRAWING && fileType != FileType.IMAGE_PHOTO) {
            throw new IllegalArgumentException("QwenVLImageParser only supports image files: " + fileType);
        }
    }

    private void validateImageFile(File file) {
        if (file.length() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("Image exceeds 10MB: " + file.getName());
        }
    }

    private String encodeToBase64(File file) {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read image: " + file.getName(), e);
        }
    }

    private FileType detectType(String fileName) {
        return FileType.IMAGE_PHOTO;
    }

    private record VisionResponse(String description, ModelTarget target) {
    }
}
