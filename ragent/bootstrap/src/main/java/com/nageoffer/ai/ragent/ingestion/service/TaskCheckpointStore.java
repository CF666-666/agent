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

import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.core.chunk.ChunkEmbeddingService;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionTaskPayloadDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionTaskPayloadMapper;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionCheckpoint;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.NodeLog;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns durable ingestion progress. Its compact interface keeps upload payload,
 * pipeline snapshot and intermediate context out of the execution engine.
 */
@Component
@RequiredArgsConstructor
public class TaskCheckpointStore implements IngestionTaskProgressStore {

    private static final long LEASE_DURATION_MS = 30 * 60 * 1000L;

    private final IngestionTaskMapper taskMapper;
    private final IngestionTaskPayloadMapper payloadMapper;
    private final ObjectMapper objectMapper;
    private final ChunkEmbeddingService chunkEmbeddingService;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    @Override
    public IngestionTaskDO initialize(IngestionTaskDO task,
                                      PipelineDefinition pipeline,
                                      byte[] rawBytes,
                                      String mimeType) {
        task.setPipelineSnapshotJson(writeJson(sanitizePipelineSnapshot(pipeline)));
        task.setResumeCount(0);
        task.setExecutionLeaseToken(newLeaseToken());
        task.setLeaseExpiresAt(nextLeaseExpiry());
        taskMapper.insert(task);
        if (rawBytes != null) {
            payloadMapper.insert(IngestionTaskPayloadDO.builder()
                    .taskId(task.getId())
                    .rawBytes(rawBytes)
                    .mimeType(mimeType)
                    .build());
        }
        return task;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    @Override
    public void checkpoint(IngestionContext context, String completedNodeId, String nextNodeId) {
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<IngestionTaskDO>()
                .set(IngestionTaskDO::getLastSuccessNodeId, completedNodeId)
                .set(IngestionTaskDO::getNextNodeId, nextNodeId)
                .setSql("checkpoint_json = CAST({0} AS jsonb)", writeJson(IngestionCheckpoint.from(context)))
                .setSql("logs_json = CAST({0} AS jsonb)", writeJson(summarizeLogs(context.getLogs())))
                .set(IngestionTaskDO::getLeaseExpiresAt, nextLeaseExpiry())
                .set(IngestionTaskDO::getUpdatedBy, UserContext.getUsername())
                .eq(IngestionTaskDO::getId, context.getTaskId())
                .eq(IngestionTaskDO::getExecutionLeaseToken, context.getExecutionLeaseToken())
                .eq(IngestionTaskDO::getStatus, IngestionStatus.RUNNING.getValue()));
        assertLeaseOwner(updated);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    @Override
    public void complete(IngestionContext context) {
        int updated = taskMapper.update(null, new LambdaUpdateWrapper<IngestionTaskDO>()
                .set(IngestionTaskDO::getStatus, context.getStatus() == null ? IngestionStatus.FAILED.getValue() : context.getStatus().getValue())
                .set(IngestionTaskDO::getChunkCount, context.getChunks() == null ? 0 : context.getChunks().size())
                .set(IngestionTaskDO::getErrorMessage, context.getError() == null ? null : context.getError().getMessage())
                .set(IngestionTaskDO::getCompletedAt, new Date())
                .set(IngestionTaskDO::getLeaseExpiresAt, null)
                .set(IngestionTaskDO::getUpdatedBy, UserContext.getUsername())
                .setSql("logs_json = CAST({0} AS jsonb)", writeJson(summarizeLogs(context.getLogs())))
                .setSql("metadata_json = CAST({0} AS jsonb)", writeJson(buildTaskMetadata(context)))
                .eq(IngestionTaskDO::getId, context.getTaskId())
                .eq(IngestionTaskDO::getExecutionLeaseToken, context.getExecutionLeaseToken())
                .eq(IngestionTaskDO::getStatus, IngestionStatus.RUNNING.getValue()));
        assertLeaseOwner(updated);
    }

    @Override
    public ResumeState restoreUpload(String taskId) {
        IngestionTaskDO task = requireTask(taskId);
        Date now = new Date();
        boolean claimable = IngestionStatus.FAILED.getValue().equals(task.getStatus())
                || (IngestionStatus.RUNNING.getValue().equals(task.getStatus())
                && task.getLeaseExpiresAt() != null && task.getLeaseExpiresAt().before(now));
        Assert.isTrue(claimable, () -> new ClientException("Task is not eligible for resume"));
        Assert.isTrue(SourceType.FILE.getValue().equals(task.getSourceType()),
                () -> new ClientException("Only uploaded file tasks can be resumed"));

        String leaseToken = newLeaseToken();
        int claimed = taskMapper.update(null, new LambdaUpdateWrapper<IngestionTaskDO>()
                .set(IngestionTaskDO::getStatus, IngestionStatus.RUNNING.getValue())
                .set(IngestionTaskDO::getErrorMessage, null)
                .set(IngestionTaskDO::getCompletedAt, null)
                .set(IngestionTaskDO::getResumeCount, (task.getResumeCount() == null ? 0 : task.getResumeCount()) + 1)
                .set(IngestionTaskDO::getExecutionLeaseToken, leaseToken)
                .set(IngestionTaskDO::getLeaseExpiresAt, nextLeaseExpiry())
                .set(IngestionTaskDO::getUpdatedBy, UserContext.getUsername())
                .eq(IngestionTaskDO::getId, taskId)
                .eq(IngestionTaskDO::getDeleted, 0)
                .and(wrapper -> wrapper.eq(IngestionTaskDO::getStatus, IngestionStatus.FAILED.getValue())
                        .or(nested -> nested.eq(IngestionTaskDO::getStatus, IngestionStatus.RUNNING.getValue())
                                .lt(IngestionTaskDO::getLeaseExpiresAt, now))));
        Assert.isTrue(claimed == 1, () -> new ClientException("Task has already been claimed for resume"));

        try {
            IngestionTaskPayloadDO payload = payloadMapper.selectById(taskId);
            Assert.notNull(payload, () -> new ClientException("Upload payload is unavailable for this task"));
            PipelineDefinition pipeline = readJson(task.getPipelineSnapshotJson(), PipelineDefinition.class,
                    "Pipeline snapshot is unavailable for this task");

            IngestionContext context = IngestionContext.builder()
                    .taskId(task.getId())
                    .pipelineId(task.getPipelineId())
                    .idempotencyKey(task.getIdempotencyKey())
                    .source(DocumentSource.builder()
                            .type(SourceType.FILE)
                            .location(task.getSourceLocation())
                            .fileName(task.getSourceFileName())
                            .build())
                    .rawBytes(payload.getRawBytes())
                    .mimeType(payload.getMimeType())
                    .logs(readLogs(task.getLogsJson()))
                    .executionLeaseToken(leaseToken)
                    .build();
            if (StringUtils.hasText(task.getCheckpointJson())) {
                IngestionCheckpoint checkpoint = readJson(task.getCheckpointJson(), IngestionCheckpoint.class,
                        "Task checkpoint cannot be restored");
                checkpoint.restoreTo(context);
                // VectorChunk#embedding is deliberately not serialized. The lease is
                // claimed first, so concurrent resume requests cannot duplicate this cost.
                if (context.getChunks() != null && !context.getChunks().isEmpty()) {
                    chunkEmbeddingService.embed(context.getChunks(), null);
                }
            }
            return new ResumeState(pipeline, context, task.getNextNodeId());
        } catch (RuntimeException exception) {
            releaseFailedResumeClaim(taskId, leaseToken, exception);
            throw exception;
        }
    }

    private void releaseFailedResumeClaim(String taskId, String leaseToken, RuntimeException exception) {
        taskMapper.update(null, new LambdaUpdateWrapper<IngestionTaskDO>()
                .set(IngestionTaskDO::getStatus, IngestionStatus.FAILED.getValue())
                .set(IngestionTaskDO::getErrorMessage, exception.getMessage())
                .set(IngestionTaskDO::getLeaseExpiresAt, null)
                .set(IngestionTaskDO::getUpdatedBy, UserContext.getUsername())
                .eq(IngestionTaskDO::getId, taskId)
                .eq(IngestionTaskDO::getExecutionLeaseToken, leaseToken)
                .eq(IngestionTaskDO::getStatus, IngestionStatus.RUNNING.getValue()));
    }

    private void assertLeaseOwner(int updated) {
        Assert.isTrue(updated == 1, () -> new ClientException("Task execution lease has been lost"));
    }

    private String newLeaseToken() {
        return UUID.randomUUID().toString();
    }

    private Date nextLeaseExpiry() {
        return new Date(System.currentTimeMillis() + LEASE_DURATION_MS);
    }

    PipelineDefinition sanitizePipelineSnapshot(PipelineDefinition pipeline) {
        JsonNode root = objectMapper.valueToTree(pipeline);
        redactSensitiveValues(root);
        try {
            return objectMapper.treeToValue(root, PipelineDefinition.class);
        } catch (Exception e) {
            throw new ClientException("Failed to sanitize pipeline snapshot");
        }
    }

    private void redactSensitiveValues(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            List<String> fieldNames = new ArrayList<>();
            objectNode.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                JsonNode value = objectNode.get(fieldName);
                if (isSensitiveField(fieldName)) {
                    objectNode.put(fieldName, "[REDACTED]");
                } else {
                    redactSensitiveValues(value);
                }
            }
            return;
        }
        if (node != null && node.isArray()) {
            node.forEach(this::redactSensitiveValues);
        }
    }

    private boolean isSensitiveField(String fieldName) {
        return fieldName != null && fieldName.matches("(?i).*?(password|secret|token|api[_-]?key|credential|authorization).*?");
    }

    private IngestionTaskDO requireTask(String taskId) {
        IngestionTaskDO task = taskMapper.selectById(taskId);
        Assert.notNull(task, () -> new ClientException("Ingestion task not found"));
        return task;
    }

    private Map<String, Object> buildTaskMetadata(IngestionContext context) {
        Map<String, Object> data = new HashMap<>();
        if (context.getMetadata() != null) {
            data.putAll(context.getMetadata());
        }
        if (context.getKeywords() != null && !context.getKeywords().isEmpty()) {
            data.put("keywords", context.getKeywords());
        }
        if (context.getQuestions() != null && !context.getQuestions().isEmpty()) {
            data.put("questions", context.getQuestions());
        }
        return data;
    }

    private List<NodeLog> summarizeLogs(List<NodeLog> logs) {
        if (logs == null) {
            return List.of();
        }
        return logs.stream().map(log -> NodeLog.builder()
                .nodeId(log.getNodeId())
                .nodeType(log.getNodeType())
                .attempt(log.getAttempt())
                .message(log.getMessage())
                .durationMs(log.getDurationMs())
                .success(log.isSuccess())
                .error(log.getError())
                .output(null)
                .build()).toList();
    }

    private List<NodeLog> readLogs(String raw) {
        if (!StringUtils.hasText(raw)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<NodeLog>>() {
            });
        } catch (Exception e) {
            throw new ClientException("Task logs cannot be restored");
        }
    }

    private <T> T readJson(String raw, Class<T> type, String errorMessage) {
        Assert.isTrue(StringUtils.hasText(raw), () -> new ClientException(errorMessage));
        try {
            return objectMapper.readValue(raw, type);
        } catch (Exception e) {
            throw new ClientException(errorMessage);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ClientException("Failed to persist ingestion task checkpoint");
        }
    }

}
