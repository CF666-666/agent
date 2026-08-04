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

package com.nageoffer.ai.ragent.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskPayloadDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskPayloadMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class TaskCheckpointStorePostgresIntegrationTest {

    @Autowired
    private TaskCheckpointStore checkpointStore;

    @Autowired
    private IngestionTaskMapper taskMapper;

    @Autowired
    private IngestionTaskPayloadMapper payloadMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String taskId;

    @AfterEach
    void cleanup() {
        if (taskId != null) {
            jdbcTemplate.update("DELETE FROM t_ingestion_task_payload WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM t_ingestion_task WHERE id = ?", taskId);
        }
    }

    @Test
    void shouldAtomicallyClaimFailedTaskAndReclaimExpiredRunningTask() throws Exception {
        taskId = String.valueOf(System.currentTimeMillis());
        taskMapper.insert(IngestionTaskDO.builder()
                .id(taskId)
                .pipelineId("pipeline-it")
                .idempotencyKey("integration-" + taskId)
                .pipelineSnapshotJson(objectMapper.writeValueAsString(PipelineDefinition.builder().nodes(List.of()).build()))
                .sourceType("file")
                .sourceLocation("integration.pdf")
                .sourceFileName("integration.pdf")
                .status(IngestionStatus.FAILED.getValue())
                .chunkCount(0)
                .resumeCount(0)
                .deleted(0)
                .build());
        payloadMapper.insert(IngestionTaskPayloadDO.builder()
                .taskId(taskId)
                .rawBytes("integration payload".getBytes())
                .mimeType("application/pdf")
                .build());

        String firstLease = checkpointStore.restoreUpload(taskId).getContext().getExecutionLeaseToken();
        assertThrows(ClientException.class, () -> checkpointStore.restoreUpload(taskId));

        IngestionTaskDO running = taskMapper.selectById(taskId);
        running.setLeaseExpiresAt(new Date(System.currentTimeMillis() - 1000));
        taskMapper.updateById(running);

        String reclaimedLease = checkpointStore.restoreUpload(taskId).getContext().getExecutionLeaseToken();
        assertNotEquals(firstLease, reclaimedLease);
    }

    @Test
    void shouldPersistCheckpointAndCompletionJsonToPostgresJsonbColumns() throws Exception {
        taskId = String.valueOf(System.currentTimeMillis());
        taskMapper.insert(IngestionTaskDO.builder()
                .id(taskId)
                .pipelineId("pipeline-it")
                .idempotencyKey("integration-jsonb-" + taskId)
                .pipelineSnapshotJson(objectMapper.writeValueAsString(PipelineDefinition.builder().nodes(List.of()).build()))
                .sourceType("file")
                .sourceLocation("integration.txt")
                .sourceFileName("integration.txt")
                .status(IngestionStatus.FAILED.getValue())
                .chunkCount(0)
                .resumeCount(0)
                .deleted(0)
                .build());
        payloadMapper.insert(IngestionTaskPayloadDO.builder()
                .taskId(taskId)
                .rawBytes("integration payload".getBytes())
                .mimeType("text/plain")
                .build());

        IngestionContext context = checkpointStore.restoreUpload(taskId).getContext();
        checkpointStore.checkpoint(context, "fetch", null);
        context.setStatus(IngestionStatus.COMPLETED);
        checkpointStore.complete(context);

        IngestionTaskDO persisted = taskMapper.selectById(taskId);
        assertEquals(IngestionStatus.COMPLETED.getValue(), persisted.getStatus());
        assertNotNull(persisted.getCheckpointJson());
        assertNotNull(persisted.getLogsJson());
        assertNotNull(persisted.getMetadataJson());
    }
}
