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

import com.nageoffer.ai.ragent.infra.embedding.FixedModelEmbeddingExecutor;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Bounded retry executor used exclusively by Phase 5 startup ingestion. */
@Slf4j
@Component
public class StartupEmbeddingRetryExecutor {

    private static final int MAX_ATTEMPTS = 3;

    private final FixedModelEmbeddingExecutor embeddingExecutor;
    private final LongConsumer sleeper;
    private final LongSupplier jitterSupplier;

    public StartupEmbeddingRetryExecutor(FixedModelEmbeddingExecutor embeddingExecutor) {
        this(embeddingExecutor, StartupEmbeddingRetryExecutor::sleep, () -> ThreadLocalRandom.current().nextLong(251));
    }

    StartupEmbeddingRetryExecutor(FixedModelEmbeddingExecutor embeddingExecutor,
                                  LongConsumer sleeper,
                                  LongSupplier jitterSupplier) {
        this.embeddingExecutor = embeddingExecutor;
        this.sleeper = sleeper;
        this.jitterSupplier = jitterSupplier;
    }

    public List<Float> embed(String modelId, String text) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long startedAt = System.nanoTime();
            try {
                return embeddingExecutor.embed(modelId, text);
            } catch (RuntimeException exception) {
                lastFailure = exception;
                long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
                if (!isTransient(exception) || attempt == MAX_ATTEMPTS) {
                    throw new StartupEmbeddingRetryException(
                            "Startup embedding failed after " + attempt + " attempt(s), model=" + modelId,
                            exception);
                }
                long delayMs = retryDelayMillis(attempt);
                log.warn("Startup embedding transient failure: model={}, attempt={}/{}, errorType={}, durationMs={}, retryInMs={}",
                        modelId, attempt, MAX_ATTEMPTS, errorType(exception), durationMs, delayMs);
                sleeper.accept(delayMs);
            }
        }
        throw new StartupEmbeddingRetryException("Startup embedding retry state exhausted", lastFailure);
    }

    private long retryDelayMillis(int failedAttempt) {
        long backoff = failedAttempt == 1 ? 1_000L : 2_000L;
        return backoff + Math.floorMod(jitterSupplier.getAsLong(), 251L);
    }

    private boolean isTransient(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ModelClientException clientException) {
                ModelClientErrorType type = clientException.getErrorType();
                return type == ModelClientErrorType.NETWORK_ERROR
                        || type == ModelClientErrorType.RATE_LIMITED
                        || type == ModelClientErrorType.SERVER_ERROR;
            }
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof InterruptedIOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String errorType(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ModelClientException clientException) {
                return clientException.getErrorType().name();
            }
            current = current.getCause();
        }
        return exception.getClass().getSimpleName();
    }

    private static void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new StartupEmbeddingRetryException("Startup embedding retry was interrupted", exception);
        }
    }
}
