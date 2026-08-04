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

package com.nageoffer.ai.ragent.ingestion.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexerNodeTest {

    @Test
    void shouldReplaceDocumentChunksWithoutDeletingBeforeWrite() {
        VectorStoreAdmin vectorStoreAdmin = mock(VectorStoreAdmin.class);
        VectorStoreService vectorStoreService = mock(VectorStoreService.class);
        when(vectorStoreAdmin.vectorSpaceExists(any(VectorSpaceId.class))).thenReturn(true);
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setCollectionName("industrial_docs");
        defaults.setDimension(2);
        IndexerNode node = new IndexerNode(new ObjectMapper(), vectorStoreAdmin, vectorStoreService, defaults);
        IngestionContext context = IngestionContext.builder()
                .taskId("task-1")
                .pipelineId("pipeline-1")
                .idempotencyKey("idempotency-key-123456")
                .chunks(List.of(VectorChunk.builder().index(0).content("bearing temperature").embedding(new float[]{0.1F, 0.2F}).build()))
                .build();

        node.execute(context, NodeConfig.builder().nodeId("index").nodeType("indexer").build());

        verify(vectorStoreService).replaceDocumentChunks(eq("industrial_docs"), eq("idempotency-key-123456"), any());
        verify(vectorStoreService, never()).deleteDocumentVectors(any(), any());
        verify(vectorStoreService, never()).indexDocumentChunks(any(), any(), any());
    }
}
