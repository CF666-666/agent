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

import com.google.gson.JsonPrimitive;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReferenceSourceIdResolverTest {

    private final RetrievedChunk chunk = RetrievedChunk.builder().id("chunk-42").build();

    @Test
    void explicitSourceIdWinsOverChannelConventions() {
        assertEquals("document-7", ReferenceSourceIdResolver.resolve(chunk,
                Map.of("sourceId", new JsonPrimitive("document-7"), "source", "HYPERGRAPH")));
    }

    @Test
    void imageReferencesUseOriginalImagePath() {
        assertEquals("drawings/pump-01.png", ReferenceSourceIdResolver.resolve(chunk,
                Map.of("source", "IMAGE_SEMANTIC", "imagePath", "drawings/pump-01.png")));
    }

    @Test
    void hypergraphReferencesUseHyperedgeId() {
        assertEquals("chunk-42", ReferenceSourceIdResolver.resolve(chunk, Map.of("source", "HYPERGRAPH")));
    }

    @Test
    void textReferencesPreferSourceFileAndFallBackToChunkId() {
        assertEquals("sop/pump.md", ReferenceSourceIdResolver.resolve(chunk,
                Map.of("source", "VECTOR_GLOBAL", "sourceFile", "sop/pump.md")));
        assertEquals("chunk-42", ReferenceSourceIdResolver.resolve(chunk, Map.of("source", "VECTOR_GLOBAL")));
    }
}
