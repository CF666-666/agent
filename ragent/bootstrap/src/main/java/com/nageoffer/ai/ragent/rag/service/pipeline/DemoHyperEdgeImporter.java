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

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.rag.core.hypergraph.EntityNode;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdge;
import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdgeDocumentStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Explicitly imports the version-controlled demo hyperedges into the durable store.
 *
 * <p>The importer is disabled by default. It exists for local demos and evaluation
 * environments where the JSONL fixture must become an intentional persisted source;
 * it never changes the production loader's no-fallback behavior.</p>
 */
@Slf4j
@Component
@Order(15)
public class DemoHyperEdgeImporter implements CommandLineRunner {

    static final String IMPORT_PROP = "phase5.import-demo-hyperedges";
    static final Path DEFAULT_EDGE_FILE = Paths.get("data/hypergraph/hyperedges.jsonl");

    private static final Gson GSON = new Gson();

    private final Environment environment;
    private final HyperEdgeDocumentStore documentStore;
    private final Path edgeFile;

    @Autowired
    public DemoHyperEdgeImporter(Environment environment, HyperEdgeDocumentStore documentStore) {
        this(environment, documentStore, DEFAULT_EDGE_FILE);
    }

    DemoHyperEdgeImporter(Environment environment, HyperEdgeDocumentStore documentStore, Path edgeFile) {
        this.environment = environment;
        this.documentStore = documentStore;
        this.edgeFile = edgeFile;
    }

    @Override
    public void run(String... args) throws Exception {
        if (!"true".equals(environment.getProperty(IMPORT_PROP))) {
            return;
        }
        if (!Files.isRegularFile(edgeFile)) {
            throw new IllegalStateException("Demo hyperedge fixture is missing: " + edgeFile.toAbsolutePath());
        }

        List<HyperEdge> edges = readEdges(edgeFile);
        if (edges.isEmpty()) {
            throw new IllegalStateException("Demo hyperedge fixture is empty: " + edgeFile.toAbsolutePath());
        }

        Map<String, List<HyperEdge>> edgesByDocument = new LinkedHashMap<>();
        for (HyperEdge edge : edges) {
            edgesByDocument.computeIfAbsent(edge.getSourceDocument(), ignored -> new ArrayList<>()).add(edge);
        }
        for (Map.Entry<String, List<HyperEdge>> entry : edgesByDocument.entrySet()) {
            documentStore.replaceDocument(entry.getKey(), entry.getValue());
        }
        log.info("Imported {} demo hyperedges for {} documents from {}", edges.size(),
                edgesByDocument.size(), edgeFile.toAbsolutePath());
    }

    private List<HyperEdge> readEdges(Path source) throws IOException {
        List<HyperEdge> edges = new ArrayList<>();
        List<String> lines = Files.readAllLines(source);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            edges.add(parseEdge(line, index + 1));
        }
        return edges;
    }

    private HyperEdge parseEdge(String line, int lineNumber) {
        try {
            JsonObject object = GSON.fromJson(line, JsonObject.class);
            if (object == null) {
                throw new IllegalArgumentException("must be a JSON object");
            }
            String edgeId = requiredString(object, "edgeId", lineNumber);
            String sourceDocument = requiredString(object, "sourceDocument", lineNumber);
            HyperEdge.HyperEdgeBuilder builder = HyperEdge.builder()
                    .edgeId(edgeId)
                    .sourceDocument(sourceDocument)
                    .equipment(optionalString(object, "equipment"))
                    .condition(optionalString(object, "condition"))
                    .parameter(optionalString(object, "parameter"))
                    .fault(optionalString(object, "fault"))
                    .sopDoc(optionalString(object, "sopDoc"))
                    .extendedEntities(parseExtendedEntities(object));
            return builder.build();
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid demo hyperedge at line " + lineNumber, exception);
        }
    }

    private String requiredString(JsonObject object, String field, int lineNumber) {
        String value = optionalString(object, field);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + field + " at line " + lineNumber);
        }
        return value;
    }

    private String optionalString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return null;
        }
        String value = element.getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    private List<EntityNode> parseExtendedEntities(JsonObject object) {
        JsonElement element = object.get("extendedEntities");
        if (element == null || !element.isJsonArray()) {
            return List.of();
        }
        List<EntityNode> entities = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("extendedEntities must contain objects");
            }
            JsonObject entity = item.getAsJsonObject();
            String label = optionalString(entity, "label");
            String value = optionalString(entity, "value");
            if (label == null && value == null) {
                throw new IllegalArgumentException("extended entity must contain label or value");
            }
            entities.add(new EntityNode(label, value));
        }
        return entities;
    }
}
