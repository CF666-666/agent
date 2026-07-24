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

package com.nageoffer.ai.ragent.multimodal.parser.dto;

/**
 * 多模态文件类型枚举
 */
public enum FileType {

    /** 电子 PDF / Word / Excel（文本层可提取） */
    PDF_ELECTRONIC,

    /** 扫描版 PDF / 图片（需 OCR） */
    PDF_SCANNED,

    /** 设备工程图纸（如爆炸图、装配图） */
    IMAGE_DRAWING,

    /** 现场设备照片 */
    IMAGE_PHOTO,

    /** 维修操作视频 */
    VIDEO;

    /** 是否为图像类（图纸或照片） */
    public boolean isImage() {
        return this == IMAGE_DRAWING || this == IMAGE_PHOTO;
    }

    /** 是否需要 OCR 处理 */
    public boolean needsOCR() {
        return this == PDF_SCANNED;
    }

    /** 是否为视频 */
    public boolean isVideo() {
        return this == VIDEO;
    }
}
