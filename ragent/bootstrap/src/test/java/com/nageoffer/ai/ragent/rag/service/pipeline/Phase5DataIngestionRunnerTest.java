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

import com.nageoffer.ai.ragent.multimodal.retrieval.image.ImageIngestionService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Phase5DataIngestionRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldCleanPartialFaqsSkipImagesAndFailStartupWhenRetriesAreExhausted() throws Exception {
        Path faqFile = write("faq.jsonl", "{\"question\":\"pump maintenance\",\"answer\":\"inspect bearings\"}\n");
        Path imageFile = write("images.jsonl", "{\"description\":\"pump drawing\",\"image_path\":\"drawings/pump.jpg\"}\n");
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        StartupEmbeddingRetryExecutor retryExecutor = mock(StartupEmbeddingRetryExecutor.class);
        ImageIngestionService imageIngestion = mock(ImageIngestionService.class);
        Environment environment = environment();
        when(vectorStoreAdmin.vectorSpaceExists(any())).thenReturn(true);
        when(retryExecutor.embed(anyString(), anyString())).thenThrow(new StartupEmbeddingRetryException("exhausted", null));

        Phase5DataIngestionRunner runner = new Phase5DataIngestionRunner(
                vectorStore, vectorStoreAdmin, imageIngestion, environment,
                retryExecutor, faqFile, imageFile);

        assertThatThrownBy(() -> runner.run())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Phase 5 ingestion failed");

        verify(vectorStore, times(2)).deleteDocumentVectors("rag_default_store", "phase5_faq");
        verify(vectorStore, never()).indexDocumentChunks(anyString(), anyString(), anyList());
        verifyNoInteractions(imageIngestion);
    }

    @Test
    void shouldRejectFaqBatchWhenPersistedCountDiffersFromSuccessfulEmbeddings() throws Exception {
        Path faqFile = write("faq.jsonl", "{\"question\":\"pump maintenance\",\"answer\":\"inspect bearings\"}\n");
        Path imageFile = write("images.jsonl", "");
        VectorStoreService vectorStore = mock(VectorStoreService.class);
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        StartupEmbeddingRetryExecutor retryExecutor = mock(StartupEmbeddingRetryExecutor.class);
        ImageIngestionService imageIngestion = mock(ImageIngestionService.class);
        when(vectorStoreAdmin.vectorSpaceExists(any())).thenReturn(true);
        when(retryExecutor.embed("qwen-emb-8b", "pump maintenance\ninspect bearings"))
                .thenReturn(java.util.List.of(0.1F, 0.2F));
        when(vectorStore.countDocumentChunks("rag_default_store", "phase5_faq")).thenReturn(0L);

        Phase5DataIngestionRunner runner = new Phase5DataIngestionRunner(
                vectorStore, vectorStoreAdmin, imageIngestion, environment(),
                retryExecutor, faqFile, imageFile);

        assertThatThrownBy(() -> runner.run()).isInstanceOf(IllegalStateException.class);

        verify(vectorStore).indexDocumentChunks(anyString(), anyString(), anyList());
        verify(vectorStore, times(2)).deleteDocumentVectors("rag_default_store", "phase5_faq");
        verifyNoInteractions(imageIngestion);
    }

    private Environment environment() {
        Environment environment = mock(Environment.class);
        when(environment.getProperty("phase5.ingest")).thenReturn("true");
        when(environment.getProperty("phase5.startup-embedding.model-id", "qwen-emb-8b"))
                .thenReturn("qwen-emb-8b");
        return environment;
    }

    private Path write(String fileName, String content) throws Exception {
        Path path = tempDir.resolve(fileName);
        Files.writeString(path, content);
        return path;
    }
}
