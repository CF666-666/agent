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
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeExecutionOutcome;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.node.IngestionNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeExecutionRunnerTest {

    @Test
    void shouldRetryUntilASafeNodeSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        IngestionNode flakyNode = new IngestionNode() {
            @Override
            public String getNodeType() {
                return "fetcher";
            }

            @Override
            public NodeResult execute(IngestionContext context, NodeConfig config) {
                return calls.incrementAndGet() == 1
                        ? NodeResult.fail(new IllegalStateException("temporary"))
                        : NodeResult.ok("recovered");
            }
        };
        NodeConfig config = NodeConfig.builder()
                .nodeId("fetch")
                .nodeType("fetcher")
                .executionPolicy(NodeExecutionPolicy.builder().maxAttempts(2).retryBackoffMs(0L).build())
                .build();

        NodeExecutionOutcome outcome = new NodeExecutionRunner()
                .execute(flakyNode, IngestionContext.builder().build(), config);

        assertEquals(2, calls.get());
        assertEquals(2, outcome.getAttempts().size());
        assertEquals(1, outcome.getAttempts().get(0).getAttempt());
        assertEquals(2, outcome.getAttempts().get(1).getAttempt());
        assertEquals(true, outcome.getResult().isSuccess());
    }
}
