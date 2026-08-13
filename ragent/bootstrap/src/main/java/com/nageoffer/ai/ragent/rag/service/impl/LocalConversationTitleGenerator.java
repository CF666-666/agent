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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.service.ConversationTitleGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalConversationTitleGenerator implements ConversationTitleGenerator {
    private static final String DEFAULT_TITLE = "新对话";
    /**
     * 配置缺失或非法（null / <=0）时回退的标题长度。
     * 与 {@link MemoryProperties#titleMaxLength} 的默认值保持一致，避免在配置注入失败时截断过短或 NPE。
     */
    private static final int FALLBACK_MAX_LENGTH = 30;
    private final MemoryProperties memoryProperties;

    @Override
    public String generate(String question) {
        if (question == null || question.isBlank()) return DEFAULT_TITLE;
        String normalized = question.trim().replaceAll("\\s+", " ");
        Integer configured = memoryProperties.getTitleMaxLength();
        // titleMaxLength 正常经 @Min(10)/@Max(100) 校验；此处仅兜底 null/非正值的极端场景
        int maxCodePoints = configured == null || configured <= 0 ? FALLBACK_MAX_LENGTH : configured;
        if (normalized.codePointCount(0, normalized.length()) <= maxCodePoints) return normalized;
        return normalized.substring(0, normalized.offsetByCodePoints(0, maxCodePoints));
    }
}
