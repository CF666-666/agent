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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentChunkLogMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeDocumentScheduleService;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdgeDocumentLifecycle;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceImplDeleteTest {

    @Mock
    private KnowledgeDocumentMapper documentMapper;
    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;
    @Mock
    private KnowledgeChunkService knowledgeChunkService;
    @Mock
    private KnowledgeDocumentScheduleService scheduleService;
    @Mock
    private KnowledgeDocumentChunkLogMapper chunkLogMapper;
    @Mock
    private VectorStoreService vectorStoreService;
    @Mock
    private HyperEdgeDocumentLifecycle hyperEdgeDocumentLifecycle;
    @InjectMocks
    private KnowledgeDocumentServiceImpl service;

    @Test
    void shouldRemovePipelineHyperedgesWhenKnowledgeDocumentIsDeleted() {
        KnowledgeDocumentDO document = KnowledgeDocumentDO.builder()
                .id("doc-1")
                .kbId("kb-1")
                .build();
        KnowledgeBaseDO knowledgeBase = KnowledgeBaseDO.builder().collectionName("industrial-kb").build();
        when(documentMapper.selectById("doc-1")).thenReturn(document);
        when(knowledgeBaseMapper.selectById("kb-1")).thenReturn(knowledgeBase);

        service.delete("doc-1");

        verify(hyperEdgeDocumentLifecycle).removeDocument("ingestion:doc-1");
    }
}
