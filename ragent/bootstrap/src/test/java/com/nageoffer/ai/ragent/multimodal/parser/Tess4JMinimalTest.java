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
import net.sourceforge.tess4j.TesseractException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Tess4J 最小可用性测试：不设自定义 datapath，用 Tess4J 内置默认
 */
public class Tess4JMinimalTest {

    public static void main(String[] args) throws Exception {
        BufferedImage image = new BufferedImage(400, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 400, 80);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.PLAIN, 20));
        g.drawString("Hello World 123", 20, 50);
        g.dispose();

        File f = File.createTempFile("minimal-", ".png");
        f.deleteOnExit();
        ImageIO.write(image, "png", f);
        System.out.println("Image: " + f.getAbsolutePath());

        // 不设任何 datapath，全用默认
        Tesseract t = new Tesseract();
        t.setLanguage("eng");
        t.setPageSegMode(3);

        try {
            String text = t.doOCR(f);
            System.out.println("OCR SUCCESS: " + text.trim());
        } catch (TesseractException e) {
            System.out.println("OCR FAILED: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
