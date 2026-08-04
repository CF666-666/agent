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

package com.nageoffer.ai.ragent.ingestion.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Creates a stable identity for one source version processed by one pipeline definition. */
@Component
public class IngestionIdempotencyKeyFactory {

    private final ObjectMapper objectMapper;

    public IngestionIdempotencyKeyFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String create(DocumentSource source, byte[] rawBytes, PipelineDefinition pipeline, String vectorSpaceName) {
        String sourceFingerprint = rawBytes == null || rawBytes.length == 0
                ? sourceFingerprint(source)
                : sha256(rawBytes);
        String pipelineFingerprint = sha256(write(pipeline));
        return sha256(sourceFingerprint + "|" + pipelineFingerprint + "|" + nullSafe(vectorSpaceName));
    }

    public String deterministicChunkId(String idempotencyKey, int chunkIndex) {
        return idempotencyKey.substring(0, 14) + String.format("%06x", chunkIndex);
    }

    private String sourceFingerprint(DocumentSource source) {
        if (source == null) {
            return "unknown";
        }
        String type = source.getType() == null ? "" : source.getType().getValue();
        return type + "|" + nullSafe(source.getLocation()) + "|" + nullSafe(source.getFileName());
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fingerprint ingestion pipeline", e);
        }
    }

    private String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create ingestion idempotency key", e);
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
