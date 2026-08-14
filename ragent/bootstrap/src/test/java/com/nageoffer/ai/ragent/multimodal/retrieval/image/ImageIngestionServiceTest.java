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

package com.nageoffer.ai.ragent.multimodal.retrieval.image;

import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageIngestionServiceTest {

    @Test
    void shouldUseProvidedVectorWithoutRoutingEmbedding() {
        VectorStoreAdmin admin = mock(VectorStoreAdmin.class);
        VectorStoreService store = mock(VectorStoreService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        RAGDefaultProperties properties = mock(RAGDefaultProperties.class);
        ImageIngestionService service = new ImageIngestionService(admin, store, embedding, properties);

        List<Float> vector = List.of(0.1F, 0.2F, 0.3F);
        service.ingest("pump drawing", "drawings/pump.jpg", "drawings/pump.jpg", "Qwen-VL",
                Map.of("license", "proprietary"), vector);

        verify(embedding, never()).embed(anyString());
        verify(store).indexDocumentChunks(eq("industrial_images"), anyString(), anyList());
    }

    @Test
    void shouldDelegateToRoutingEmbeddingWhenVectorAbsent() {
        VectorStoreAdmin admin = mock(VectorStoreAdmin.class);
        VectorStoreService store = mock(VectorStoreService.class);
        EmbeddingService embedding = mock(EmbeddingService.class);
        RAGDefaultProperties properties = mock(RAGDefaultProperties.class);
        ImageIngestionService service = new ImageIngestionService(admin, store, embedding, properties);

        when(embedding.embed("pump drawing")).thenReturn(List.of(0.1F, 0.2F, 0.3F));
        service.ingest("pump drawing", "drawings/pump.jpg", "drawings/pump.jpg", "Qwen-VL",
                Map.of("license", "proprietary"));

        verify(embedding).embed("pump drawing");
        verify(store).indexDocumentChunks(eq("industrial_images"), anyString(), anyList());
    }
}
