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

import net.sourceforge.tess4j.Tesseract;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Tess4J 调试测试：先测英文（内置 eng 语言包），再测中文，定位问题
 */
public class Tess4JDebugTest {

    public static void main(String[] args) throws Exception {
        // 生成简单英文图片
        BufferedImage enImage = new BufferedImage(400, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = enImage.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 80);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.PLAIN, 20));
        g.drawString("Hello World from Tesseract", 20, 50);
        g.dispose();
        File enFile = File.createTempFile("test-en-", ".png");
        enFile.deleteOnExit();
        ImageIO.write(enImage, "png", enFile);

        // 测试 1：英文 OCR（Tess4J 内置 eng.traineddata）
        System.out.println("=== 测试 1：英文 OCR (内置 eng) ===");
        Tesseract tesseract = new Tesseract();
        tesseract.setDatapath(System.getProperty("user.dir") + "/tessdata");
        tesseract.setLanguage("eng");

        try {
            // eng.traineddata 应该在 tessdata 目录下，如果没有则用默认路径
            String text1 = tesseract.doOCR(enFile);
            System.out.println("英文 OCR 结果: " + text1.trim());
        } catch (Exception e) {
            System.out.println("英文 OCR 失败: " + e.getMessage());
        }

        // 测试 2：中文 OCR（chi_sim.traineddata）
        System.out.println("\n=== 测试 2：中文 OCR (chi_sim) ===");
        Tesseract t2 = new Tesseract();
        t2.setDatapath(System.getProperty("user.dir") + "/tessdata");
        t2.setLanguage("chi_sim");
        t2.setOcrEngineMode(1);

        try {
            System.out.println("tessdata 目录文件列表:");
            File dir = new File(System.getProperty("user.dir") + "/tessdata");
            for (File f : dir.listFiles()) {
                System.out.println("  " + f.getName() + " (" + f.length() + " bytes)");
            }
        } catch (Exception e) {
            System.out.println("无法列出目录: " + e.getMessage());
        }

        // 生成中文图片
        BufferedImage zhImage = new BufferedImage(600, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = zhImage.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, 600, 100);
        g2.setColor(Color.BLACK);
        g2.setFont(new Font("SimSun", Font.PLAIN, 24));
        g2.drawString("测试中文OCR识别", 20, 50);
        g2.dispose();
        File zhFile = File.createTempFile("test-zh-", ".png");
        zhFile.deleteOnExit();
        ImageIO.write(zhImage, "png", zhFile);

        try {
            String text2 = t2.doOCR(zhFile);
            System.out.println("\n中文 OCR 结果: " + text2.trim());
        } catch (Exception e) {
            System.out.println("中文 OCR 失败: " + e.getMessage());
        }
    }
}
