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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalConversationTitleGeneratorTest {
    @Test
    void shouldNormalizeAndTruncateByUnicodeCodePoint() {
        MemoryProperties properties = new MemoryProperties();
        properties.setTitleMaxLength(10);
        LocalConversationTitleGenerator generator = new LocalConversationTitleGenerator(properties);
        assertThat(generator.generate("  pump\n bearing   temperature abnormal  ")).isEqualTo("pump beari");
        assertThat(generator.generate("😀设备温度异常需要如何处理")).isEqualTo("😀设备温度异常需要如");
    }

    @Test
    void shouldReturnStableFallbackForBlankInput() {
        LocalConversationTitleGenerator generator = new LocalConversationTitleGenerator(new MemoryProperties());
        assertThat(generator.generate(null)).isEqualTo("新对话");
        assertThat(generator.generate("   \n ")).isEqualTo("新对话");
        assertThat(generator.generate("轴承温度异常")).isEqualTo(generator.generate("轴承温度异常"));
    }

    @Test
    void shouldFallbackToDefaultLengthWhenConfigMissingOrInvalid() {
        MemoryProperties nullConfig = new MemoryProperties();
        nullConfig.setTitleMaxLength(null);
        LocalConversationTitleGenerator nullGenerator = new LocalConversationTitleGenerator(nullConfig);
        String longQuestion = "设".repeat(40);
        String nullResult = nullGenerator.generate(longQuestion);
        assertThat(nullResult.codePointCount(0, nullResult.length())).isEqualTo(30);

        MemoryProperties invalidConfig = new MemoryProperties();
        invalidConfig.setTitleMaxLength(0);
        LocalConversationTitleGenerator invalidGenerator = new LocalConversationTitleGenerator(invalidConfig);
        String invalidResult = invalidGenerator.generate(longQuestion);
        assertThat(invalidResult.codePointCount(0, invalidResult.length())).isEqualTo(30);
    }
}
