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

import com.nageoffer.ai.ragent.multimodal.parser.dto.FileType;
import com.nageoffer.ai.ragent.multimodal.parser.dto.ParseResult;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Tess4JParser 手动测试
 * <p>
 * 程序生成一张含中文工业文本的图片，验证 OCR 提取。
 */
public class Tess4JParserManualTest {

    public static void main(String[] args) throws Exception {
        // 1. 生成一张含中文工业文本的测试图片
        BufferedImage image = new BufferedImage(800, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 300);

        g.setColor(Color.BLACK);
        g.setFont(new Font("SimSun", Font.PLAIN, 24));
        g.drawString("2号轧机主轴承维护手册", 50, 60);
        g.drawString("设备型号: ZJ-2000-01", 50, 100);
        g.drawString("润滑油规格: Shell Omala S4 GX 320", 50, 140);
        g.drawString("维护周期: 每季度一次，冬季每月一次", 50, 180);
        g.dispose();

        File testImage = File.createTempFile("test-ocr-", ".png");
        testImage.deleteOnExit();
        ImageIO.write(image, "png", testImage);
        System.out.println("[OK] 生成测试图片: " + testImage.getAbsolutePath());

        // 2. 创建解析器（模拟 Spring 流程：构造 + init）
        System.out.println("\n--- 初始化 Tess4JParser ---");
        Tess4JParser parser = new Tess4JParser();
        parser.init(); // @PostConstruct 等价调用

        // 3. OCR 解析
        System.out.println("\n--- 开始 OCR ---");
        ParseResult result = parser.parse(testImage, FileType.PDF_SCANNED);

        // 4. 输出结果
        System.out.println("\n=== OCR 解析结果 ===");
        System.out.println("来源文件: " + result.getSourceFile());
        System.out.println("文件类型: " + result.getFileType());
        System.out.println("解析器:   " + result.getMetadata().get("parser"));
        System.out.println("OCR 语言:  " + result.getMetadata().get("ocrLanguage"));
        System.out.println("文本长度: " + result.getTextContent().length() + " 字符");
        System.out.println("\n--- 提取的文本 ---");
        System.out.println(result.getTextContent());
        System.out.println("======================");

        // 5. 断言
        String text = result.getTextContent();
        boolean pass1 = text.contains("轧机") || text.contains("轧") || text.contains("2号");
        boolean pass2 = text.contains("Shell") || text.contains("Omala");
        boolean pass3 = text.contains("ZJ") || text.contains("2000");
        boolean pass4 = result.getTextContent().length() > 20;

        System.out.println("\n=== 验证结果 ===");
        System.out.println("包含轧机相关文字:  " + pass1);
        System.out.println("包含 Shell Omala:  " + pass2);
        System.out.println("包含 ZJ-2000:      " + pass3);
        System.out.println("文本长度 >20 字符:  " + pass4);
        System.out.println("全部通过:          " + (pass1 && pass2 && pass3 && pass4));
    }
}
