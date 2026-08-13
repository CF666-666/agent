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

package com.nageoffer.ai.ragent.rag.rewrite;

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.CancellableChatCall;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.rewrite.MultiQuestionRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.LIGHTWEIGHT_TASK_MODEL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MultiQuestionRewriteServiceModelBindingTest {

    @Test
    void shouldBindLightweightModelAndCapTokensForRewrite() {
        LLMService llmService = mock(LLMService.class);
        RAGConfigProperties config = new RAGConfigProperties();
        config.setQueryRewriteEnabled(true);
        config.setQueryRewriteTimeoutMillis(10000L);
        QueryTermMappingService termMapping = mock(QueryTermMappingService.class);
        PromptTemplateLoader promptLoader = mock(PromptTemplateLoader.class);

        when(termMapping.normalize(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(promptLoader.load(anyString())).thenReturn("rewrite prompt");
        CancellableChatCall call = mock(CancellableChatCall.class);
        when(call.execute()).thenReturn(
                "{\"rewrite\":\"发电机轴承温度异常处理\",\"sub_questions\":[\"发电机轴承温度异常处理\"]}");
        when(llmService.startChat(any(ChatRequest.class), eq(LIGHTWEIGHT_TASK_MODEL_ID))).thenReturn(call);

        MultiQuestionRewriteService service = new MultiQuestionRewriteService(
                llmService, config, termMapping, promptLoader);

        RewriteResult result = service.rewriteWithSplit("发电机轴承温度异常升高", List.of());

        assertThat(result.rewrittenQuestion()).isEqualTo("发电机轴承温度异常处理");

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).startChat(captor.capture(), eq(LIGHTWEIGHT_TASK_MODEL_ID));
        assertThat(captor.getValue().getMaxTokens()).isEqualTo(512);
        verify(llmService, never()).startChat(any(ChatRequest.class));
    }
}
