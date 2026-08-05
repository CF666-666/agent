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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.rerank.RerankService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankPostProcessorTest {

    @Test
    void shouldUseRerankedCandidatesWhenServiceSucceeds() {
        RerankService rerankService = mock(RerankService.class);
        RerankPostProcessor processor = new RerankPostProcessor(rerankService);
        List<RetrievedChunk> candidates = List.of(chunk("first"), chunk("second"));
        List<RetrievedChunk> reranked = List.of(chunk("second"));
        when(rerankService.rerank(anyString(), anyList(), anyInt())).thenReturn(reranked);

        List<RetrievedChunk> result = processor.process(candidates, List.of(), context(1));

        assertThat(result).containsExactlyElementsOf(reranked);
    }

    @Test
    void shouldReturnTopKCandidatesWhenRerankServiceFails() {
        RerankService rerankService = mock(RerankService.class);
        RerankPostProcessor processor = new RerankPostProcessor(rerankService);
        List<RetrievedChunk> candidates = List.of(chunk("first"), chunk("second"), chunk("third"));
        when(rerankService.rerank(anyString(), anyList(), anyInt()))
                .thenThrow(new IllegalStateException("rerank unavailable"));

        List<RetrievedChunk> result = processor.process(candidates, List.of(), context(2));

        assertThat(result).extracting(RetrievedChunk::getId).containsExactly("first", "second");
    }

    private static SearchContext context(int topK) {
        return SearchContext.builder().originalQuestion("test question").topK(topK).build();
    }

    private static RetrievedChunk chunk(String id) {
        return RetrievedChunk.builder().id(id).text(id).score(1.0F).build();
    }
}
