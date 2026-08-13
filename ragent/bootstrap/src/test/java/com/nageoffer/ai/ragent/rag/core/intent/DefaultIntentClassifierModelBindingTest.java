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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.LIGHTWEIGHT_TASK_MODEL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultIntentClassifierModelBindingTest {

    @Test
    void shouldBindLightweightModelAndCapTokensForIntentClassification() {
        LLMService llmService = mock(LLMService.class);
        IntentNodeMapper intentNodeMapper = mock(IntentNodeMapper.class);
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        IntentTreeCacheManager intentTreeCacheManager = mock(IntentTreeCacheManager.class);

        when(intentTreeCacheManager.getIntentTreeFromCache()).thenReturn(null);
        when(intentNodeMapper.selectList(any())).thenReturn(List.of());
        when(promptTemplateLoader.render(anyString(), anyMap())).thenReturn("intent prompt");
        when(llmService.chat(any(ChatRequest.class), eq(LIGHTWEIGHT_TASK_MODEL_ID))).thenReturn("[]");

        DefaultIntentClassifier classifier = new DefaultIntentClassifier(
                llmService, intentNodeMapper, promptTemplateLoader, intentTreeCacheManager);

        classifier.classifyTargets("发电机轴承温度异常");

        ArgumentCaptor<ChatRequest> requestCaptor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(requestCaptor.capture(), eq(LIGHTWEIGHT_TASK_MODEL_ID));
        assertThat(requestCaptor.getValue().getMaxTokens()).isEqualTo(512);
        verify(llmService, never()).chat(any(ChatRequest.class));
    }
}
