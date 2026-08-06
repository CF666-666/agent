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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.dto.RetrievalOptions;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RAGChatControllerTest {

    @Test
    void forwardsExplicitRetrievalSwitchesToChatService() {
        RAGChatService chatService = mock(RAGChatService.class);
        RAGChatController controller = new RAGChatController(chatService, properties());

        controller.chat("设备图纸中的阀门位置", "conversation-1", false,
                true, false, false, false, true);

        ArgumentCaptor<RetrievalOptions> optionsCaptor = ArgumentCaptor.forClass(RetrievalOptions.class);
        verify(chatService).streamChat(eq("设备图纸中的阀门位置"), eq("conversation-1"), eq(false),
                optionsCaptor.capture(), any());
        assertEquals(new RetrievalOptions(true, false, false, false, true), optionsCaptor.getValue());
    }

    @Test
    void keepsAllCapabilitiesEnabledWhenSwitchesAreAbsent() {
        RAGChatService chatService = mock(RAGChatService.class);
        RAGChatController controller = new RAGChatController(chatService, properties());

        controller.chat("设备故障原因", null, false, null, null, null, null, null);

        ArgumentCaptor<RetrievalOptions> optionsCaptor = ArgumentCaptor.forClass(RetrievalOptions.class);
        verify(chatService).streamChat(eq("设备故障原因"), eq(null), eq(false),
                optionsCaptor.capture(), any());
        assertEquals(RetrievalOptions.defaults(), optionsCaptor.getValue());
    }

    private RAGDefaultProperties properties() {
        RAGDefaultProperties properties = mock(RAGDefaultProperties.class);
        when(properties.getSseTimeoutMs()).thenReturn(1_000L);
        return properties;
    }
}
