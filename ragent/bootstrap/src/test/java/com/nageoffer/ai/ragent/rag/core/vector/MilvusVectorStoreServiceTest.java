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

package com.nageoffer.ai.ragent.rag.core.vector;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.UpsertResp;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MilvusVectorStoreServiceTest {

    @Test
    void shouldUpsertReplacementChunksBeforeDeletingStaleChunks() {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        when(milvusClient.upsert(any(UpsertReq.class))).thenReturn(mock(UpsertResp.class));
        when(milvusClient.delete(any(DeleteReq.class))).thenReturn(mock(DeleteResp.class));
        RAGDefaultProperties defaults = new RAGDefaultProperties();
        defaults.setDimension(2);
        MilvusVectorStoreService store = new MilvusVectorStoreService(milvusClient, defaults);

        store.replaceDocumentChunks("industrial_docs", "doc-1", List.of(
                VectorChunk.builder().chunkId("chunk-1").index(0).content("bearing temperature")
                        .embedding(new float[]{0.1F, 0.2F}).build()));

        InOrder order = inOrder(milvusClient);
        order.verify(milvusClient).upsert(any(UpsertReq.class));
        order.verify(milvusClient).delete(any(DeleteReq.class));
    }
}
