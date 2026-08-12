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
import com.google.gson.JsonObject;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.kb.VectorCollectionAlreadyExistsException;
import com.nageoffer.ai.ragent.multimodal.retrieval.image.ImageIngestionService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceId;
import com.nageoffer.ai.ragent.rag.core.vector.VectorSpaceSpec;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreAdmin;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rebuilds the demonstration FAQ and image indexes at application startup.
 *
 * <p>The FAQ batch is fail-closed: a failed embedding or a final count mismatch
 * removes partial FAQ records, skips image ingestion, and fails application
 * startup. This prevents a partial collection from being mistaken for a valid
 * evaluation baseline.</p>
 */
@Slf4j
@Component
public class Phase5DataIngestionRunner implements CommandLineRunner {

    private static final String PROP_KEY = "phase5.ingest";
    private static final String STARTUP_EMBEDDING_MODEL_KEY = "phase5.startup-embedding.model-id";
    private static final String DEFAULT_STARTUP_EMBEDDING_MODEL = "qwen-emb-8b";
    private static final String FAQ_COLLECTION = "rag_default_store";
    private static final String FAQ_DOC_ID = "phase5_faq";
    private static final String IMAGE_COLLECTION = "industrial_images";
    private static final Path FAQ_FILE = Paths.get("data/faq/industrial_faq.jsonl");
    private static final Path DESC_FILE = Paths.get("data/images/descriptions.jsonl");
    private static final Gson GSON = new Gson();

    private final VectorStoreService vectorStoreService;
    private final VectorStoreAdmin vectorStoreAdmin;
    private final ImageIngestionService imageIngestionService;
    private final Environment env;
    private final StartupEmbeddingRetryExecutor startupEmbeddingRetryExecutor;
    private final Path faqFile;
    private final Path descriptionFile;

    public Phase5DataIngestionRunner(VectorStoreService vectorStoreService,
                                     VectorStoreAdmin vectorStoreAdmin,
                                     ImageIngestionService imageIngestionService,
                                     Environment env,
                                     StartupEmbeddingRetryExecutor startupEmbeddingRetryExecutor) {
        this(vectorStoreService, vectorStoreAdmin, imageIngestionService, env,
                startupEmbeddingRetryExecutor, FAQ_FILE, DESC_FILE);
    }

    Phase5DataIngestionRunner(VectorStoreService vectorStoreService,
                              VectorStoreAdmin vectorStoreAdmin,
                              ImageIngestionService imageIngestionService,
                              Environment env,
                              StartupEmbeddingRetryExecutor startupEmbeddingRetryExecutor,
                              Path faqFile,
                              Path descriptionFile) {
        this.vectorStoreService = vectorStoreService;
        this.vectorStoreAdmin = vectorStoreAdmin;
        this.imageIngestionService = imageIngestionService;
        this.env = env;
        this.startupEmbeddingRetryExecutor = startupEmbeddingRetryExecutor;
        this.faqFile = faqFile;
        this.descriptionFile = descriptionFile;
    }

    @Override
    public void run(String... args) {
        if (!Boolean.parseBoolean(env.getProperty(PROP_KEY))) {
            return;
        }
        log.info("====== Phase 5: starting full data ingestion ======");
        try {
            FaqIngestionSummary faq = ingestFaqs();
            int imageCount = ingestImages();
            log.info("====== Phase 5: full data ingestion completed, faqCount={}, imageCount={} ======",
                    faq.persistedCount(), imageCount);
        } catch (Exception exception) {
            log.error("Phase 5 ingestion failed; the application will not start with a partial baseline", exception);
            throw new IllegalStateException("Phase 5 ingestion failed", exception);
        }
    }

    private FaqIngestionSummary ingestFaqs() throws Exception {
        if (!Files.isRegularFile(faqFile)) {
            throw new IllegalStateException("FAQ file does not exist: " + faqFile.toAbsolutePath());
        }

        ensureVectorSpace(FAQ_COLLECTION, "Phase5 FAQ knowledge base");
        List<FaqEntry> faqs = readFaqs();
        if (faqs.isEmpty()) {
            throw new IllegalStateException("No valid FAQ entries were found: " + faqFile.toAbsolutePath());
        }

        String modelId = env.getProperty(STARTUP_EMBEDDING_MODEL_KEY, DEFAULT_STARTUP_EMBEDDING_MODEL);
        clearFaqDocument(false);
        int embeddedCount = 0;
        try {
            for (FaqEntry faq : faqs) {
                List<Float> vector = startupEmbeddingRetryExecutor.embed(modelId, faq.content());
                vectorStoreService.indexDocumentChunks(FAQ_COLLECTION, FAQ_DOC_ID, List.of(toChunk(faq, vector)));
                embeddedCount++;
                if (embeddedCount % 50 == 0 || embeddedCount == faqs.size()) {
                    log.info("FAQ ingestion progress: {}/{}", embeddedCount, faqs.size());
                }
            }

            long persistedCount = vectorStoreService.countDocumentChunks(FAQ_COLLECTION, FAQ_DOC_ID);
            if (embeddedCount != faqs.size() || persistedCount != faqs.size()) {
                throw new IllegalStateException("FAQ ingestion count mismatch: input=" + faqs.size()
                        + ", embedded=" + embeddedCount + ", persisted=" + persistedCount);
            }
            log.info("FAQ ingestion completed: input={}, embedded={}, persisted={}, model={}",
                    faqs.size(), embeddedCount, persistedCount, modelId);
            return new FaqIngestionSummary(faqs.size(), embeddedCount, persistedCount);
        } catch (Exception exception) {
            clearFaqDocument(true);
            throw exception;
        }
    }

    private List<FaqEntry> readFaqs() throws Exception {
        List<FaqEntry> entries = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(faqFile)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                JsonObject faq = GSON.fromJson(line, JsonObject.class);
                if (faq == null || !faq.has("question") || !faq.has("answer")) {
                    throw new IllegalStateException("Invalid FAQ entry at line " + lineNumber);
                }
                String question = faq.get("question").getAsString();
                String answer = faq.get("answer").getAsString();
                if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
                    throw new IllegalStateException("Blank FAQ question or answer at line " + lineNumber);
                }
                entries.add(new FaqEntry(entries.size(), question + "\n" + answer));
            }
        }
        return entries;
    }

    private VectorChunk toChunk(FaqEntry faq, List<Float> vector) {
        float[] embedding = new float[vector.size()];
        for (int index = 0; index < vector.size(); index++) {
            embedding[index] = vector.get(index);
        }
        return VectorChunk.builder()
                .chunkId(UUID.randomUUID().toString())
                .index(faq.index())
                .content(faq.content())
                .metadata(Map.of("source", FAQ_DOC_ID))
                .embedding(embedding)
                .build();
    }

    private void clearFaqDocument(boolean cleanupAfterFailure) {
        try {
            vectorStoreService.deleteDocumentVectors(FAQ_COLLECTION, FAQ_DOC_ID);
        } catch (Exception exception) {
            if (cleanupAfterFailure) {
                log.error("Failed to remove partial FAQ vectors after startup ingestion failure", exception);
            } else {
                log.warn("Failed to clear existing FAQ vectors before startup ingestion", exception);
            }
        }
    }

    private int ingestImages() throws Exception {
        if (!Files.isRegularFile(descriptionFile)) {
            throw new IllegalStateException("Image description file does not exist: " + descriptionFile.toAbsolutePath());
        }
        try {
            vectorStoreService.clearCollection(IMAGE_COLLECTION);
        } catch (Exception exception) {
            log.warn("Failed to clear image collection before startup ingestion", exception);
        }

        int total = 0;
        try (BufferedReader reader = Files.newBufferedReader(descriptionFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject description = GSON.fromJson(line, JsonObject.class);
                if (description == null || !description.has("description") || !description.has("image_path")) {
                    throw new IllegalStateException("Invalid image description entry");
                }
                String text = description.get("description").getAsString();
                String imagePath = description.get("image_path").getAsString();
                String license = description.has("license") ? description.get("license").getAsString() : "";
                imageIngestionService.ingest(text, imagePath, imagePath, "Qwen-VL",
                        Map.of("license", license, "category", "industrial_equipment"));
                total++;
            }
        }
        log.info("Image ingestion completed: count={}", total);
        return total;
    }

    private void ensureVectorSpace(String collectionName, String remark) {
        VectorSpaceId spaceId = VectorSpaceId.builder().logicalName(collectionName).build();
        if (vectorStoreAdmin.vectorSpaceExists(spaceId)) {
            return;
        }
        try {
            vectorStoreAdmin.ensureVectorSpace(VectorSpaceSpec.builder().spaceId(spaceId).remark(remark).build());
            log.info("Created vector collection: {}", collectionName);
        } catch (VectorCollectionAlreadyExistsException exception) {
            log.info("Vector collection already exists: {}", collectionName);
        }
    }

    private record FaqEntry(int index, String content) {
    }

    private record FaqIngestionSummary(int inputCount, int embeddedCount, long persistedCount) {
    }
}
