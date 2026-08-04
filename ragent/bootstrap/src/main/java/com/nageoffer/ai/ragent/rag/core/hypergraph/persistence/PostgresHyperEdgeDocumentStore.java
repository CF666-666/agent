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

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.rag.core.hypergraph.EntityNode;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdge;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdgeDocumentStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class PostgresHyperEdgeDocumentStore implements HyperEdgeDocumentStore {

    private static final TypeReference<List<EntityNode>> ENTITY_LIST = new TypeReference<>() {
    };

    private final HyperEdgeMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void replaceDocument(String sourceDocument, List<HyperEdge> edges) {
        mapper.deleteBySourceDocument(sourceDocument);
        if (edges == null || edges.isEmpty()) {
            return;
        }
        for (HyperEdge edge : edges) {
            mapper.insert(toDataObject(edge));
        }
    }

    @Override
    public List<HyperEdge> loadActiveHyperedges() {
        return mapper.selectList(new LambdaQueryWrapper<HyperEdgeDO>()
                        .eq(HyperEdgeDO::getDeleted, 0))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private HyperEdgeDO toDataObject(HyperEdge edge) {
        return HyperEdgeDO.builder()
                .id(edge.getEdgeId())
                .equipment(edge.getEquipment())
                .condition(edge.getCondition())
                .parameter(edge.getParameter())
                .fault(edge.getFault())
                .sopDoc(edge.getSopDoc())
                .extendedEntitiesJson(writeEntities(edge.getExtendedEntities()))
                .sourceDocument(edge.getSourceDocument())
                .sourceChunkId(edge.getSourceChunkId())
                .sourceChunkIndex(edge.getSourceChunkIndex())
                .sourcePage(edge.getSourcePage())
                .documentVersion(edge.getDocumentVersion())
                .deleted(0)
                .build();
    }

    private HyperEdge toDomain(HyperEdgeDO data) {
        return HyperEdge.builder()
                .edgeId(data.getId())
                .equipment(data.getEquipment())
                .condition(data.getCondition())
                .parameter(data.getParameter())
                .fault(data.getFault())
                .sopDoc(data.getSopDoc())
                .extendedEntities(readEntities(data.getExtendedEntitiesJson()))
                .sourceDocument(data.getSourceDocument())
                .sourceChunkId(data.getSourceChunkId())
                .sourceChunkIndex(data.getSourceChunkIndex())
                .sourcePage(data.getSourcePage())
                .documentVersion(data.getDocumentVersion())
                .build();
    }

    private String writeEntities(List<EntityNode> entities) {
        try {
            return objectMapper.writeValueAsString(entities == null ? List.of() : entities);
        } catch (Exception exception) {
            throw new ClientException("Unable to serialize hyperedge entities");
        }
    }

    private List<EntityNode> readEntities(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, ENTITY_LIST);
        } catch (Exception exception) {
            throw new ClientException("Unable to restore hyperedge entities");
        }
    }
}
