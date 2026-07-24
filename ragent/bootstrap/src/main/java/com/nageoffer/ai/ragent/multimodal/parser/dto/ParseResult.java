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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模态文档解析结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseResult {

    /** 原始文件路径 */
    private String sourceFile;

    /** 文件类型 */
    private FileType fileType;

    /** 提取/生成的结构化文本内容 */
    private String textContent;

    /** 视觉语义描述（仅图纸、照片、视频关键帧有，由 Qwen-VL 生成） */
    private String visualDescription;

    /** 附加元数据（OCR 置信度、视频时间戳、图片来源等） */
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();

    /** 切分后的文本块（入库时由 ChunkerNode 填充，解析阶段可为空） */
    @Builder.Default
    private List<ChunkRef> chunks = new ArrayList<>();

    /**
     * 文本块引用（轻量结构，避免与现有 Chunk 模块耦合）
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkRef {
        private String chunkId;
        private String content;
        private int index;
    }
}
