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

package com.nageoffer.ai.ragent.ingestion.domain.context;

import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A serializable snapshot of the state produced by successfully completed nodes.
 * Raw upload bytes and task identity live outside this object so the checkpoint
 * can be safely stored as JSONB and restored independently from its payload.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IngestionCheckpoint {

    private String rawText;

    private StructuredDocument document;

    private List<VectorChunk> chunks;

    private String enhancedText;

    private List<String> keywords;

    private List<String> questions;

    private Map<String, Object> metadata;

    private VectorSpaceId vectorSpaceId;

    public static IngestionCheckpoint from(IngestionContext context) {
        return IngestionCheckpoint.builder()
                .rawText(context.getRawText())
                .document(context.getDocument())
                .chunks(context.getChunks())
                .enhancedText(context.getEnhancedText())
                .keywords(context.getKeywords())
                .questions(context.getQuestions())
                .metadata(context.getMetadata())
                .vectorSpaceId(context.getVectorSpaceId())
                .build();
    }

    public void restoreTo(IngestionContext context) {
        context.setRawText(rawText);
        context.setDocument(document);
        context.setChunks(chunks);
        context.setEnhancedText(enhancedText);
        context.setKeywords(keywords);
        context.setQuestions(questions);
        context.setMetadata(metadata);
        context.setVectorSpaceId(vectorSpaceId);
    }
}
