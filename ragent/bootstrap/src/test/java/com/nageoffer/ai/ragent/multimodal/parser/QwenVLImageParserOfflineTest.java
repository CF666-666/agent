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

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

/**
 * QwenVLImageParser 离线自测（不需要 API Key）
 * <p>
 * 验证: Base64 编码 + 请求体构建 + extractText 解析
 */
public class QwenVLImageParserOfflineTest {

    private static final Gson gson = new Gson();

    public static void main(String[] args) throws Exception {
        boolean allPassed = true;

        allPassed &= testEncodeToBase64();
        allPassed &= testBuildRequest();
        allPassed &= testExtractTextNormal();
        allPassed &= testExtractTextDefensive();

        System.out.println("\n========================================");
        System.out.println("ALL PASSED: " + allPassed);
    }

    /** 测试 1: Base64 编码 */
    static boolean testEncodeToBase64() throws Exception {
        System.out.println("=== 测试 1: Base64 编码 ===");

        BufferedImage img = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 50);
        g.dispose();

        File f = File.createTempFile("test-", ".png");
        f.deleteOnExit();
        ImageIO.write(img, "png", f);

        String base64 = Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(f.toPath()));
        boolean ok = base64 != null && base64.length() > 100;
        System.out.println("Base64 length: " + base64.length() + " -> " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** 测试 2: 请求体结构 */
    static boolean testBuildRequest() {
        System.out.println("\n=== 测试 2: 请求体结构 ===");

        String base64 = Base64.getEncoder().encodeToString("test-image-data".getBytes());

        JsonObject request = new JsonObject();
        request.addProperty("model", "qwen-vl-max");
        JsonObject input = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("image", "data:image/jpeg;base64," + base64);
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", "描述");
        content.add(imagePart);
        content.add(textPart);
        message.add("content", content);
        messages.add(message);
        input.add("messages", messages);
        request.add("input", input);

        String json = gson.toJson(request);
        boolean ok = json.contains("qwen-vl-max")
                && json.contains("\"image\"")
                && json.contains("\"text\"")
                && json.contains("\"input\"");

        System.out.println("Request JSON contains required keys: " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** 测试 3: extractText 正常解析 */
    static boolean testExtractTextNormal() {
        System.out.println("\n=== 测试 3: extractText 正常解析 ===");

        String mockResponse = """
            {
              "output": {
                "choices": [{
                  "message": {
                    "content": [
                      {"text": "这是一台卧式数控车床"},
                      {"text": "，型号为CK6150"}
                    ]
                  }
                }]
              }
            }""";

        try {
            QwenVLImageParser parser = new QwenVLImageParser(
                    null, null, null);
            Method method = QwenVLImageParser.class.getDeclaredMethod("extractText", String.class);
            method.setAccessible(true);
            String result = (String) method.invoke(parser, mockResponse);

            boolean ok = result.contains("卧式数控车床") && result.contains("CK6150")
                    && !result.contains("[") && !result.contains("{");
            System.out.println("Extracted: " + result + " -> " + (ok ? "PASS" : "FAIL"));
            return ok;
        } catch (Exception e) {
            System.out.println("FAIL: " + e.getMessage());
            return false;
        }
    }

    /** 测试 4: extractText 防御解析（错误响应） */
    static boolean testExtractTextDefensive() {
        System.out.println("\n=== 测试 4: extractText 防御解析 ===");

        String errorResponse = """
            {"code":"InvalidParameter","message":"Required body invalid"}""";

        try {
            QwenVLImageParser parser = new QwenVLImageParser(null, null, null);
            Method method = QwenVLImageParser.class.getDeclaredMethod("extractText", String.class);
            method.setAccessible(true);
            method.invoke(parser, errorResponse);
            System.out.println("FAIL: should have thrown exception");
            return false;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            boolean ok = cause != null && cause.getMessage().contains("缺少 output");
            System.out.println((ok ? "PASS" : "FAIL") + ": " + (cause != null ? cause.getMessage() : e.getMessage()));
            return ok;
        }
    }
}
