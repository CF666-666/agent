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

import com.nageoffer.ai.ragent.infra.embedding.FixedModelEmbeddingExecutor;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StartupEmbeddingRetryExecutorTest {

    @Test
    void shouldRetryTransientFailuresTwiceThenSucceedWithExactlyThreeFixedModelCalls() {
        FixedModelEmbeddingExecutor embeddingExecutor = mock(FixedModelEmbeddingExecutor.class);
        when(embeddingExecutor.embed("qwen-emb-8b", "pump maintenance"))
                .thenThrow(transientFailure())
                .thenThrow(transientFailure())
                .thenReturn(List.of(0.1F, 0.2F));
        StartupEmbeddingRetryExecutor retryExecutor = new StartupEmbeddingRetryExecutor(
                embeddingExecutor, ignored -> { }, () -> 0L);

        assertThat(retryExecutor.embed("qwen-emb-8b", "pump maintenance"))
                .containsExactly(0.1F, 0.2F);

        verify(embeddingExecutor, times(3)).embed("qwen-emb-8b", "pump maintenance");
    }

    @Test
    void shouldFailImmediatelyForPermanentModelErrors() {
        FixedModelEmbeddingExecutor embeddingExecutor = mock(FixedModelEmbeddingExecutor.class);
        when(embeddingExecutor.embed("qwen-emb-8b", "pump maintenance"))
                .thenThrow(new ModelClientException("invalid request", ModelClientErrorType.CLIENT_ERROR, 400));
        StartupEmbeddingRetryExecutor retryExecutor = new StartupEmbeddingRetryExecutor(
                embeddingExecutor, ignored -> { }, () -> 0L);

        assertThatThrownBy(() -> retryExecutor.embed("qwen-emb-8b", "pump maintenance"))
                .isInstanceOf(StartupEmbeddingRetryException.class);

        verify(embeddingExecutor).embed("qwen-emb-8b", "pump maintenance");
    }

    private ModelClientException transientFailure() {
        return new ModelClientException("timeout", ModelClientErrorType.NETWORK_ERROR, null);
    }
}
