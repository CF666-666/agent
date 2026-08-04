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

package com.nageoffer.ai.ragent.rag.core.hypergraph;

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HyperEdgeExtractorTest {

    @Test
    void shouldPropagateLlmFailureInsteadOfTreatingItAsEmptyExtraction() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("provider unavailable"));
        HyperEdgeExtractor extractor = new HyperEdgeExtractor(llmService);

        assertThrows(IllegalStateException.class,
                () -> extractor.extractHyperedges("Fan-1 overload trip", "ingestion:document-1"));
    }

    @Test
    void shouldPropagateMalformedJsonInsteadOfTreatingItAsEmptyExtraction() {
        LLMService llmService = mock(LLMService.class);
        when(llmService.chat(any(ChatRequest.class))).thenReturn("not-json");
        HyperEdgeExtractor extractor = new HyperEdgeExtractor(llmService);

        assertThrows(IllegalStateException.class,
                () -> extractor.extractHyperedges("Fan-1 overload trip", "ingestion:document-1"));
    }
}
