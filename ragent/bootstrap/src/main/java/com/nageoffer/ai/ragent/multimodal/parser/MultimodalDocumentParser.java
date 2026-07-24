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

import java.io.File;
import java.util.List;

/**
 * 多模态文档解析管道接口
 * <p>
 * 将非纯文本文档（扫描件、图纸、照片、视频）转换为可供检索的语义文本。
 * 不同文件类型由对应的 {@code Parser} 实现类处理。
 */
public interface MultimodalDocumentParser {

    /**
     * 解析单个文档
     *
     * @param file     原始文件
     * @param fileType 文件类型
     * @return 解析结果（文本 + 元数据）
     */
    ParseResult parse(File file, FileType fileType);

    /**
     * 批量解析（入库时调用）
     *
     * @param files 文件列表
     * @return 解析结果列表
     */
    List<ParseResult> batchParse(List<File> files);
}
