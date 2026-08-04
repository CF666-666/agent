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
import com.nageoffer.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskPayloadDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskPayloadMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionCheckpoint;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
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

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ChunkEmbeddingService chunkEmbeddingService;

    private String taskId;

    @AfterEach
    void cleanup() {
        if (taskId != null) {
            jdbcTemplate.update("DELETE FROM t_ingestion_task_node WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM t_ingestion_task_payload WHERE task_id = ?", taskId);
            jdbcTemplate.update("DELETE FROM t_ingestion_task WHERE id = ?", taskId);
        }
    }

    @Test
    void shouldResumeFailedUploadThroughAuthenticatedHttpEndpoint() throws Exception {
        taskId = String.valueOf(System.currentTimeMillis());
        taskMapper.insert(newUploadTask(PipelineDefinition.builder()
                        .id("pipeline-http")
                        .nodes(List.of(new com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig(
                                "fetch", "fetcher", null, null, null, null)))
                        .edges(List.of())
                        .build(), IngestionStatus.FAILED));
        insertPayload("integration payload".getBytes());

        String loginBody = mockMvc.perform(post("/auth/login")
                        .contentType(APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(loginBody).at("/data/token").asText();

        mockMvc.perform(post("/ingestion/tasks/{id}/resume", taskId)
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("completed"));

        IngestionTaskDO persisted = taskMapper.selectById(taskId);
        assertEquals(IngestionStatus.COMPLETED.getValue(), persisted.getStatus());
        assertEquals(1, persisted.getResumeCount());
        assertEquals("fetch", persisted.getLastSuccessNodeId());
        assertEquals(null, persisted.getLeaseExpiresAt());
    }

    @Test
    void shouldClaimLeaseBeforeReembeddingCheckpointChunks() throws Exception {
        taskId = String.valueOf(System.currentTimeMillis());
        IngestionCheckpoint checkpoint = IngestionCheckpoint.builder()
                .chunks(List.of(VectorChunk.builder().chunkId("checkpoint-1").content("checkpoint content").build()))
                .build();
        IngestionTaskDO task = newUploadTask(PipelineDefinition.builder().nodes(List.of()).build(), IngestionStatus.FAILED);
        task.setCheckpointJson(objectMapper.writeValueAsString(checkpoint));
        task.setChunkCount(1);
        taskMapper.insert(task);
        insertPayload("checkpoint payload".getBytes());
        doAnswer(invocation -> {
            IngestionTaskDO claimed = taskMapper.selectById(taskId);
            assertEquals(IngestionStatus.RUNNING.getValue(), claimed.getStatus());
            assertNotNull(claimed.getExecutionLeaseToken());
            assertTrue(claimed.getLeaseExpiresAt().after(new Date()));
            return null;
        }).when(chunkEmbeddingService).embed(any(), isNull());

        checkpointStore.restoreUpload(taskId);

        verify(chunkEmbeddingService, times(1)).embed(any(), isNull());
    }

    @Test
    void shouldAtomicallyClaimFailedTaskAndReclaimExpiredRunningTask() throws Exception {
        taskId = String.valueOf(System.currentTimeMillis());
        taskMapper.insert(newUploadTask(PipelineDefinition.builder().nodes(List.of()).build(), IngestionStatus.FAILED));
        insertPayload("integration payload".getBytes());

        String firstLease = checkpointStore.restoreUpload(taskId).getContext().getExecutionLeaseToken();
        assertThrows(ClientException.class, () -> checkpointStore.restoreUpload(taskId));

        IngestionTaskDO running = taskMapper.selectById(taskId);
        running.setLeaseExpiresAt(new Date(System.currentTimeMillis() - 1000));
        taskMapper.updateById(running);

        String reclaimedLease = checkpointStore.restoreUpload(taskId).getContext().getExecutionLeaseToken();
        assertNotEquals(firstLease, reclaimedLease);
    }

    @Test
    void shouldRejectCheckpointAndCompletionFromExpiredLeaseWithoutChangingProgress() throws Exception {
        taskId = String.valueOf(System.currentTimeMillis());
        Date expiredAt = new Date(System.currentTimeMillis() - 1000);
        IngestionTaskDO task = newUploadTask(PipelineDefinition.builder().nodes(List.of()).build(), IngestionStatus.RUNNING);
        task.setPipelineId("lease");
        task.setIdempotencyKey(taskId);
        task.setLastSuccessNodeId("previous");
        task.setNextNodeId("next");
        task.setExecutionLeaseToken("expired-lease-token");
        task.setLeaseExpiresAt(expiredAt);
        taskMapper.insert(task);
        IngestionContext context = IngestionContext.builder()
                .taskId(taskId)
                .executionLeaseToken("expired-lease-token")
                .logs(List.of())
                .build();

        assertThrows(ClientException.class, () -> checkpointStore.checkpoint(context, "stale", null));
        context.setStatus(IngestionStatus.COMPLETED);
        assertThrows(ClientException.class, () -> checkpointStore.complete(context));

        IngestionTaskDO persisted = taskMapper.selectById(taskId);
        assertEquals(IngestionStatus.RUNNING.getValue(), persisted.getStatus());
        assertEquals("previous", persisted.getLastSuccessNodeId());
        assertEquals("next", persisted.getNextNodeId());
        assertEquals("expired-lease-token", persisted.getExecutionLeaseToken());
        assertEquals(expiredAt, persisted.getLeaseExpiresAt());
    }

    @Test
    void shouldPersistCheckpointAndCompletionJsonToPostgresJsonbColumns() throws Exception {
        taskId = String.valueOf(System.currentTimeMillis());
        taskMapper.insert(newUploadTask(PipelineDefinition.builder().nodes(List.of()).build(), IngestionStatus.FAILED));
        insertPayload("integration payload".getBytes());

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

    private IngestionTaskDO newUploadTask(PipelineDefinition pipeline, IngestionStatus status) throws Exception {
        return IngestionTaskDO.builder()
                .id(taskId)
                .pipelineId("pipeline-it")
                .idempotencyKey("integration-" + taskId)
                .pipelineSnapshotJson(objectMapper.writeValueAsString(pipeline))
                .sourceType("file")
                .sourceLocation("integration.txt")
                .sourceFileName("integration.txt")
                .status(status.getValue())
                .chunkCount(0)
                .resumeCount(0)
                .deleted(0)
                .build();
    }

    private void insertPayload(byte[] rawBytes) {
        payloadMapper.insert(IngestionTaskPayloadDO.builder()
                .taskId(taskId)
                .rawBytes(rawBytes)
                .mimeType("text/plain")
                .build());
    }
}
