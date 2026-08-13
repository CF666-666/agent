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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.config.StaticResourceProperties;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.RetrievalOptions;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class StreamChatPipelineTest {

    @Test
    void shouldPreserveStructuredHypergraphEvidenceInReferences() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        QueryRewriteService rewriteService = mock(QueryRewriteService.class);
        IntentResolver intentResolver = mock(IntentResolver.class);
        IntentGuidanceService guidanceService = mock(IntentGuidanceService.class);
        RetrievalEngine retrievalEngine = mock(RetrievalEngine.class);
        StreamCallback callback = mock(StreamCallback.class);
        Map<String, Object> evidence = Map.of(
                "hyperEdgeId", "edge-1",
                "sourceDocument", "manual-a",
                "sourceChunkId", "chunk-7");
        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("edge-1")
                .text("风机在高温下发生振动")
                .score(0.9F)
                .metadata(Map.of(
                        "source", "HYPERGRAPH",
                        "hyperEdgePath", "风机 → 高温 → 振动",
                        "relationEvidence", List.of(evidence)))
                .build();
        when(retrievalEngine.retrieve(any(), any(Integer.class), any())).thenReturn(
                RetrievalContext.builder()
                        .intentChunks(Map.of("relation", List.of(chunk)))
                        .build());
        StreamChatPipeline pipeline = new StreamChatPipeline(
                memoryService, rewriteService, intentResolver, guidanceService, retrievalEngine,
                mock(LLMService.class), mock(RAGPromptService.class), mock(PromptTemplateLoader.class),
                mock(StreamTaskManager.class), mock(StaticResourceProperties.class));

        pipeline.execute(StreamChatContext.builder()
                .question("风机为何振动")
                .conversationId("eval")
                .taskId("task-hypergraph")
                .callback(callback)
                .retrievalOptions(new RetrievalOptions(false, true, false, true, true))
                .build());

        ArgumentCaptor<String> references = ArgumentCaptor.forClass(String.class);
        verify(callback).onReferences(references.capture());
        org.assertj.core.api.Assertions.assertThat(references.getValue())
                .contains("relationEvidence", "edge-1", "manual-a", "chunk-7");
    }

    @Test
    void shouldCompleteAfterSendingReferencesWithoutStartingLlmInRetrievalOnlyMode() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        QueryRewriteService rewriteService = mock(QueryRewriteService.class);
        IntentResolver intentResolver = mock(IntentResolver.class);
        IntentGuidanceService guidanceService = mock(IntentGuidanceService.class);
        RetrievalEngine retrievalEngine = mock(RetrievalEngine.class);
        LLMService llmService = mock(LLMService.class);
        StreamCallback callback = mock(StreamCallback.class);
        when(intentResolver.resolve(any())).thenReturn(List.of(new SubQuestionIntent("机械臂图纸", List.of())));
        when(guidanceService.detectAmbiguity(any(), any())).thenReturn(GuidanceDecision.none());
        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("chunk-1")
                .text("机械臂图纸证据")
                .score(0.9F)
                .metadata(Map.of("source", "VECTOR_GLOBAL"))
                .build();
        when(retrievalEngine.retrieve(any(), any(Integer.class), any())).thenReturn(RetrievalContext.builder()
                .kbContext("机械臂图纸证据")
                .intentChunks(Map.of("multi-channel", List.of(chunk)))
                .build());
        StreamChatPipeline pipeline = new StreamChatPipeline(
                memoryService, rewriteService, intentResolver, guidanceService, retrievalEngine, llmService,
                mock(RAGPromptService.class), mock(PromptTemplateLoader.class), mock(StreamTaskManager.class),
                mock(StaticResourceProperties.class));

        pipeline.execute(StreamChatContext.builder()
                .question("机械臂图纸")
                .conversationId("eval")
                .taskId("task-1")
                .callback(callback)
                .retrievalOptions(new RetrievalOptions(false, true, false, true, true))
                .build());

        verify(callback).onReferences(any());
        verify(callback).onRetrievalComplete();
        org.mockito.InOrder order = inOrder(callback);
        order.verify(callback).onRetrievalStatus(any());
        order.verify(callback).onReferences(any());
        order.verify(callback).onRetrievalComplete();
        verifyNoInteractions(llmService);
        verifyNoInteractions(memoryService);
        verifyNoInteractions(intentResolver, guidanceService);
    }

    @Test
    void shouldCompleteWithoutStartingLlmWhenRetrievalOnlyModeHasNoResults() {
        ConversationMemoryService memoryService = mock(ConversationMemoryService.class);
        QueryRewriteService rewriteService = mock(QueryRewriteService.class);
        IntentResolver intentResolver = mock(IntentResolver.class);
        IntentGuidanceService guidanceService = mock(IntentGuidanceService.class);
        RetrievalEngine retrievalEngine = mock(RetrievalEngine.class);
        LLMService llmService = mock(LLMService.class);
        StreamCallback callback = mock(StreamCallback.class);
        when(intentResolver.resolve(any())).thenReturn(List.of(new SubQuestionIntent("未知设备", List.of())));
        when(guidanceService.detectAmbiguity(any(), any())).thenReturn(GuidanceDecision.none());
        when(retrievalEngine.retrieve(any(), any(Integer.class), any())).thenReturn(RetrievalContext.builder()
                .intentChunks(Map.of())
                .build());
        StreamChatPipeline pipeline = new StreamChatPipeline(
                memoryService, rewriteService, intentResolver, guidanceService, retrievalEngine, llmService,
                mock(RAGPromptService.class), mock(PromptTemplateLoader.class), mock(StreamTaskManager.class),
                mock(StaticResourceProperties.class));

        pipeline.execute(StreamChatContext.builder()
                .question("未知设备")
                .conversationId("eval")
                .taskId("task-empty")
                .callback(callback)
                .retrievalOptions(new RetrievalOptions(false, true, false, true, true))
                .build());

        verify(callback).onRetrievalComplete();
        verify(callback).onRetrievalStatus(any());
        verify(callback, never()).onReferences(any());
        verifyNoInteractions(llmService);
        verifyNoInteractions(memoryService);
        verifyNoInteractions(intentResolver, guidanceService);
    }
}
