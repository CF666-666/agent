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

package com.nageoffer.ai.ragent.rag.core.hypergraph.persistence;

import com.nageoffer.ai.ragent.rag.core.hypergraph.EntityNode;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdge;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdgeDocumentStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class PostgresHyperEdgeDocumentStoreIntegrationTest {

    private static final String SOURCE_DOCUMENT = "integration/hyperedge-store.pdf";

    @Autowired
    private HyperEdgeDocumentStore hyperEdgeStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createTable() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS t_hyperedge (
                    id VARCHAR(64) NOT NULL PRIMARY KEY,
                    equipment TEXT, condition TEXT, parameter TEXT, fault TEXT, sop_doc TEXT,
                    extended_entities_json JSONB NOT NULL DEFAULT '[]'::jsonb,
                    source_document VARCHAR(1024) NOT NULL, source_chunk_id VARCHAR(128),
                    source_chunk_index INTEGER, source_page INTEGER, document_version VARCHAR(128),
                    deleted SMALLINT NOT NULL DEFAULT 0
                )
                """);
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM t_hyperedge WHERE source_document = ?", SOURCE_DOCUMENT);
    }

    @Test
    void shouldReplaceOldDocumentEdgesAndRestoreEvidenceFromPostgres() {
        hyperEdgeStore.replaceDocument(SOURCE_DOCUMENT, List.of(
                HyperEdge.builder().edgeId("edge-old").sourceDocument(SOURCE_DOCUMENT)
                        .equipment("fan-1").fault("old-fault").documentVersion("v1").build()));

        hyperEdgeStore.replaceDocument(SOURCE_DOCUMENT, List.of(
                HyperEdge.builder().edgeId("edge-new").sourceDocument(SOURCE_DOCUMENT)
                        .sourceChunkId("doc#chunk-2").sourceChunkIndex(2).sourcePage(8)
                        .equipment("fan-1").fault("new-fault").documentVersion("v2")
                        .extendedEntities(List.of(new EntityNode("spare", "SP-01"))).build()));

        List<HyperEdge> restored = hyperEdgeStore.loadActiveHyperedges().stream()
                .filter(edge -> SOURCE_DOCUMENT.equals(edge.getSourceDocument()))
                .toList();

        assertEquals(1, restored.size());
        assertEquals("edge-new", restored.get(0).getEdgeId());
        assertEquals("v2", restored.get(0).getDocumentVersion());
        assertEquals(8, restored.get(0).getSourcePage());
        assertEquals(new EntityNode("spare", "SP-01"), restored.get(0).getExtendedEntities().get(0));
    }
}
