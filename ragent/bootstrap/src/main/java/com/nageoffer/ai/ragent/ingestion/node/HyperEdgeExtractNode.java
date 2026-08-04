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

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdge;
import com.nageoffer.ai.ragent.rag.core.hypergraph.IndustrialHyperGraph;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts industrial hyperedges from already chunked text and attaches
 * retrieval evidence before indexing the result in the in-memory hypergraph.
 */
@Component
@RequiredArgsConstructor
public class HyperEdgeExtractNode implements IngestionNode {

    static final String DOCUMENT_VERSION_METADATA_KEY = "documentVersion";
    private static final String PAGE_METADATA_KEY = "page";
    private static final String PAGE_NUMBER_METADATA_KEY = "pageNumber";

    private final IndustrialHyperGraph hyperGraph;

    @Override
    public String getNodeType() {
        return IngestionNodeType.HYPEREDGE_EXTRACT.getValue();
    }

    @Override
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<VectorChunk> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.fail(new ClientException("Hyperedge extraction requires chunked document content"));
        }

        String sourceDocument = resolveSourceDocument(context);
        List<HyperEdge> extracted = new ArrayList<>();
        try {
            for (int position = 0; position < chunks.size(); position++) {
                VectorChunk chunk = chunks.get(position);
                if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
                    continue;
                }
                List<HyperEdge> edges = hyperGraph.extractHyperedges(chunk.getContent(), sourceDocument);
                for (HyperEdge edge : edges) {
                    attachEvidence(edge, context, chunk, position, sourceDocument);
                }
                extracted.addAll(edges);
            }
            if (!extracted.isEmpty()) {
                hyperGraph.addHyperedges(extracted);
            }
            return NodeResult.ok("Extracted " + extracted.size() + " hyperedges from " + chunks.size() + " chunks");
        } catch (RuntimeException exception) {
            return NodeResult.fail(new ClientException("Hyperedge extraction failed: " + exception.getMessage()));
        }
    }

    private void attachEvidence(HyperEdge edge,
                                IngestionContext context,
                                VectorChunk chunk,
                                int position,
                                String sourceDocument) {
        edge.setSourceDocument(sourceDocument);
        edge.setSourceChunkId(resolveChunkId(context, chunk, position));
        edge.setSourceChunkIndex(chunk.getIndex() == null ? position : chunk.getIndex());
        edge.setSourcePage(readPage(chunk.getMetadata()));
        edge.setDocumentVersion(readDocumentVersion(context.getMetadata()));
    }

    private String resolveSourceDocument(IngestionContext context) {
        DocumentSource source = context.getSource();
        if (source != null && StringUtils.hasText(source.getLocation())) {
            return source.getLocation();
        }
        if (source != null && StringUtils.hasText(source.getFileName())) {
            return source.getFileName();
        }
        if (StringUtils.hasText(context.getIdempotencyKey())) {
            return "ingestion:" + context.getIdempotencyKey();
        }
        return "ingestion:" + context.getTaskId();
    }

    private String resolveChunkId(IngestionContext context, VectorChunk chunk, int position) {
        if (StringUtils.hasText(chunk.getChunkId())) {
            return chunk.getChunkId();
        }
        String documentId = StringUtils.hasText(context.getIdempotencyKey())
                ? context.getIdempotencyKey() : context.getTaskId();
        return documentId + "#chunk-" + (chunk.getIndex() == null ? position : chunk.getIndex());
    }

    private Integer readPage(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.containsKey(PAGE_METADATA_KEY)
                ? metadata.get(PAGE_METADATA_KEY) : metadata.get(PAGE_NUMBER_METADATA_KEY);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String readDocumentVersion(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object version = metadata.get(DOCUMENT_VERSION_METADATA_KEY);
        return version == null ? null : String.valueOf(version);
    }
}
