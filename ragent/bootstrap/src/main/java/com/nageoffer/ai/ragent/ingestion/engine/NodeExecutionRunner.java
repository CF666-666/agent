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

import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeExecutionPolicy;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeAttemptResult;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeExecutionOutcome;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.node.IngestionNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a retry-safe node according to its policy and returns an attempt history.
 * Callers only need to consume the final result and append the returned attempts to their logs.
 */
@Component
public class NodeExecutionRunner implements NodeExecutionExecutor {

    @Override
    public NodeExecutionOutcome execute(IngestionNode node, IngestionContext context, NodeConfig config) {
        NodeExecutionPolicy policy = config.getExecutionPolicy();
        int maxAttempts = policy == null ? NodeExecutionPolicy.DEFAULT_MAX_ATTEMPTS : policy.effectiveMaxAttempts();
        long backoffMs = policy == null ? 0L : policy.effectiveRetryBackoffMs();
        List<NodeAttemptResult> attempts = new ArrayList<>(maxAttempts);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long startedAt = System.currentTimeMillis();
            NodeResult result;
            try {
                result = node.execute(context, config);
            } catch (Exception e) {
                result = NodeResult.fail(e);
            }
            attempts.add(new NodeAttemptResult(attempt, System.currentTimeMillis() - startedAt, result));
            if (result.isSuccess() || attempt == maxAttempts) {
                return new NodeExecutionOutcome(result, attempts);
            }
            if (!sleep(backoffMs)) {
                NodeResult interrupted = NodeResult.fail(new InterruptedException("Node retry interrupted"));
                attempts.add(new NodeAttemptResult(attempt + 1, 0, interrupted));
                return new NodeExecutionOutcome(interrupted, attempts);
            }
        }
        throw new IllegalStateException("Node execution reached an unreachable state");
    }

    private boolean sleep(long backoffMs) {
        if (backoffMs <= 0L) {
            return true;
        }
        try {
            Thread.sleep(backoffMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
