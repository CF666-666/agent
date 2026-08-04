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

package com.nageoffer.ai.ragent.ingestion.domain.pipeline;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Per-node retry policy. A missing policy means one attempt without backoff. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeExecutionPolicy {

    public static final int DEFAULT_MAX_ATTEMPTS = 1;

    public static final int MAX_ALLOWED_ATTEMPTS = 5;

    public static final long MAX_ALLOWED_BACKOFF_MS = 60_000L;

    private Integer maxAttempts;

    private Long retryBackoffMs;

    public int effectiveMaxAttempts() {
        return maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
    }

    public long effectiveRetryBackoffMs() {
        return retryBackoffMs == null ? 0L : retryBackoffMs;
    }
}
