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

package com.nageoffer.ai.ragent.rag.core.hypergraph;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Removes a document's facts from durable storage before making the same
 * replacement visible to online retrieval.
 */
@Component
@RequiredArgsConstructor
public class HyperEdgeDocumentLifecycleImpl implements HyperEdgeDocumentLifecycle {

    private final HyperEdgeDocumentStore hyperEdgeStore;
    private final IndustrialHyperGraph hyperGraph;

    @Override
    public void removeDocument(String sourceDocument) {
        hyperEdgeStore.replaceDocument(sourceDocument, List.of());
        hyperGraph.replaceDocumentHyperedges(sourceDocument, List.of());
    }
}
