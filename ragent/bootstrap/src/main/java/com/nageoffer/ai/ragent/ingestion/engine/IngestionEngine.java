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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.context.IngestionContext;
import com.nageoffer.ai.ragent.ingestion.domain.context.NodeLog;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionStatus;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineGraph;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeResult;
import com.nageoffer.ai.ragent.ingestion.domain.result.NodeExecutionOutcome;
import com.nageoffer.ai.ragent.ingestion.node.IngestionNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 流水线执行引擎 - 基于节点连线的链式执行
 */
@Slf4j
@Component
public class IngestionEngine {

    private final Map<String, IngestionNode> nodeMap;
    private final ConditionEvaluator conditionEvaluator;
    private final NodeOutputExtractor outputExtractor;
    private final NodeExecutionExecutor executionRunner;

    public IngestionEngine(
            List<IngestionNode> nodes,
            ConditionEvaluator conditionEvaluator,
            NodeOutputExtractor outputExtractor,
            NodeExecutionExecutor executionRunner) {
        this.nodeMap = nodes.stream()
                .collect(Collectors.toMap(IngestionNode::getNodeType, n -> n));
        this.conditionEvaluator = conditionEvaluator;
        this.outputExtractor = outputExtractor;
        this.executionRunner = executionRunner;
    }

    /**
     * 执行流水线
     */
    public IngestionContext execute(PipelineDefinition pipeline, IngestionContext context) {
        return execute(pipeline, context, null, null);
    }

    /**
     * Execute a pipeline from its graph start or an already validated checkpoint node.
     * The optional listener is called only after a successful node and resolved route.
     */
    public IngestionContext execute(PipelineDefinition pipeline,
                                    IngestionContext context,
                                    String resumeNodeId,
                                    IngestionExecutionListener listener) {
        if (context.getLogs() == null) {
            context.setLogs(new ArrayList<>());
        }
        context.setStatus(IngestionStatus.RUNNING);

        PipelineGraph graph = PipelineGraph.of(pipeline);
        String startNodeId = resumeNodeId == null ? graph.startNodeId() : resumeNodeId;
        if (graph.node(startNodeId) == null) {
            throw new ClientException("Resume node does not exist in pipeline: " + startNodeId);
        }

        log.info("流水线从节点开始执行: {}", startNodeId);

        executeChain(startNodeId, graph, context, listener);

        if (context.getStatus() == IngestionStatus.RUNNING) {
            context.setStatus(IngestionStatus.COMPLETED);
        }
        return context;
    }

    /**
     * 链式执行节点
     */
    private void executeChain(
            String nodeId,
            PipelineGraph graph,
            IngestionContext context,
            IngestionExecutionListener listener) {

        String currentNodeId = nodeId;
        int executedCount = 0;
        final int maxNodes = graph.size();

        while (currentNodeId != null) {
            // 防止无限循环（理论上不会发生，因为已经验证过了）
            if (executedCount++ >= maxNodes) {
                throw new ClientException("执行节点数超过上限，可能存在死循环");
            }

            NodeConfig config = graph.node(currentNodeId);
            if (config == null) {
                log.warn("未找到节点配置: {}", currentNodeId);
                break;
            }

            log.info("开始执行节点: {}", currentNodeId);
            NodeResult result = executeNode(context, config);

            if (!result.isSuccess()) {
                context.setStatus(IngestionStatus.FAILED);
                context.setError(result.getError());
                log.error("节点 {} 执行失败: {}", currentNodeId, result.getMessage());

                break;
            }

            if (!result.isShouldContinue()) {
                notifyNodeSuccess(listener, context, currentNodeId, null);
                log.info("流水线在节点 {} 停止", currentNodeId);
                break;
            }

            // 根据满足条件的最高优先级边选择下一个节点，未命中时使用默认边。
            try {
                currentNodeId = graph.resolveNextNodeId(currentNodeId, context, conditionEvaluator);
            } catch (RuntimeException e) {
                context.setStatus(IngestionStatus.FAILED);
                context.setError(e);
                context.getLogs().add(NodeLog.builder()
                        .nodeId(config.getNodeId())
                        .nodeType(config.getNodeType())
                        .message("Route selection failed: " + e.getMessage())
                        .durationMs(0)
                        .success(false)
                        .error(e.getMessage())
                        .build());
                log.error("节点 {} 路由选择失败", currentNodeId, e);
                break;
            }
            notifyNodeSuccess(listener, context, config.getNodeId(), currentNodeId);
        }

        log.info("流水线执行完成，共执行 {} 个节点", executedCount);
    }

    private void notifyNodeSuccess(IngestionExecutionListener listener,
                                   IngestionContext context,
                                   String completedNodeId,
                                   String nextNodeId) {
        if (listener != null) {
            listener.afterNodeSuccess(context, completedNodeId, nextNodeId);
        }
    }

    /**
     * 执行单个节点
     */
    private NodeResult executeNode(IngestionContext context, NodeConfig nodeConfig) {
        String nodeType = nodeConfig.getNodeType();
        String nodeId = nodeConfig.getNodeId();

        IngestionNode node = nodeMap.get(nodeType);
        if (node == null) {
            return NodeResult.fail(new IllegalStateException("未找到节点类型: " + nodeType));
        }

        // 条件检查
        if (nodeConfig.getCondition() != null && !nodeConfig.getCondition().isNull()) {
            boolean conditionMatched;
            try {
                conditionMatched = conditionEvaluator.evaluate(context, nodeConfig.getCondition());
            } catch (RuntimeException e) {
                context.getLogs().add(NodeLog.builder()
                        .nodeId(nodeId)
                        .nodeType(nodeType)
                        .message("Node condition evaluation failed: " + e.getMessage())
                        .durationMs(0)
                        .success(false)
                        .error(e.getMessage())
                        .build());
                return NodeResult.fail(e);
            }
            if (!conditionMatched) {
                NodeResult skip = NodeResult.skip("条件未满足");
                context.getLogs().add(NodeLog.builder()
                        .nodeId(nodeId)
                        .nodeType(nodeType)
                        .message(skip.getMessage())
                        .durationMs(0)
                        .success(true)
                        .output(outputExtractor.extract(context, nodeConfig))
                        .build());
                return skip;
            }
        }

        // 执行节点
        NodeExecutionOutcome outcome = executionRunner.execute(node, context, nodeConfig);
        outcome.getAttempts().forEach(attempt -> {
            context.getLogs().add(NodeLog.builder()
                    .nodeId(nodeId)
                    .nodeType(nodeType)
                    .attempt(attempt.getAttempt())
                    .message(attempt.getResult().getMessage())
                    .durationMs(attempt.getDurationMs())
                    .success(attempt.getResult().isSuccess())
                    .error(attempt.getResult().getError() == null ? null : attempt.getResult().getError().getMessage())
                    .output(outputExtractor.extract(context, nodeConfig))
                    .build());
        });
        NodeResult result = outcome.getResult();
        log.info("节点 {} 执行完成，共 {} 次尝试: {}", nodeId, outcome.getAttempts().size(), result.getMessage());
        return result;
    }
}
