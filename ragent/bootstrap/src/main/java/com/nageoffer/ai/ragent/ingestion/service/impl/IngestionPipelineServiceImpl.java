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

package com.nageoffer.ai.ragent.ingestion.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineCreateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineEdgeRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineNodeRequest;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionPipelineUpdateRequest;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineEdgeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineNodeVO;
import com.nageoffer.ai.ragent.ingestion.controller.vo.IngestionPipelineVO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineEdgeDO;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineNodeDO;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineEdgeMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineMapper;
import com.nageoffer.ai.ragent.ingestion.dao.mapper.IngestionPipelineNodeMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.domain.enums.IngestionNodeType;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeEdge;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeConfig;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineGraph;
import com.nageoffer.ai.ragent.ingestion.domain.pipeline.PipelineDefinition;
import com.nageoffer.ai.ragent.ingestion.service.IngestionPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 数据清洗流水线业务逻辑实现
 */
@Service
@RequiredArgsConstructor
public class IngestionPipelineServiceImpl implements IngestionPipelineService {

    private final IngestionPipelineMapper pipelineMapper;
    private final IngestionPipelineNodeMapper nodeMapper;
    private final IngestionPipelineEdgeMapper edgeMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionPipelineVO create(IngestionPipelineCreateRequest request) {
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        List<NodeConfig> nodes = toNodeConfigs(request.getNodes());
        List<NodeEdge> edges = toNodeEdges(request.getEdges());
        validateDefinition(null, request.getName(), request.getDescription(), nodes, edges);
        IngestionPipelineDO pipeline = IngestionPipelineDO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .createdBy(UserContext.getUsername())
                .updatedBy(UserContext.getUsername())
                .build();
        try {
            pipelineMapper.insert(pipeline);
        } catch (DuplicateKeyException dke) {
            throw new ClientException("流水线名称已存在");
        }
        replaceNodes(pipeline.getId(), request.getNodes());
        replaceEdges(pipeline.getId(), request.getEdges());
        return toVO(pipeline, fetchNodes(pipeline.getId()), fetchEdges(pipeline.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IngestionPipelineVO update(String pipelineId, IngestionPipelineUpdateRequest request) {
        Assert.notNull(request, () -> new ClientException("请求不能为空"));
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));

        List<IngestionPipelineNodeDO> persistedNodes = fetchNodes(pipeline.getId());
        List<IngestionPipelineEdgeDO> persistedEdges = fetchEdges(pipeline.getId());
        List<NodeConfig> nodes = request.getNodes() == null
                ? persistedNodes.stream().map(this::toNodeConfig).toList()
                : toNodeConfigs(request.getNodes());
        List<NodeEdge> edges = request.getEdges() == null
                ? persistedEdges.stream().map(this::toNodeEdge).toList()
                : toNodeEdges(request.getEdges());
        String candidateName = StringUtils.hasText(request.getName()) ? request.getName() : pipeline.getName();
        String candidateDescription = request.getDescription() == null ? pipeline.getDescription() : request.getDescription();
        validateDefinition(pipeline.getId(), candidateName, candidateDescription, nodes, edges);

        if (StringUtils.hasText(request.getName())) {
            pipeline.setName(candidateName);
        }
        if (request.getDescription() != null) {
            pipeline.setDescription(request.getDescription());
        }
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.updateById(pipeline);

        if (request.getNodes() != null) {
            replaceNodes(pipeline.getId(), request.getNodes());
        }
        if (request.getEdges() != null) {
            replaceEdges(pipeline.getId(), request.getEdges());
        }
        return toVO(pipeline, fetchNodes(pipeline.getId()), fetchEdges(pipeline.getId()));
    }

    @Override
    public IngestionPipelineVO get(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        return toVO(pipeline, fetchNodes(pipeline.getId()), fetchEdges(pipeline.getId()));
    }

    @Override
    public IPage<IngestionPipelineVO> page(Page<IngestionPipelineVO> page, String keyword) {
        Page<IngestionPipelineDO> mpPage = new Page<>(page.getCurrent(), page.getSize());
        LambdaQueryWrapper<IngestionPipelineDO> qw = new LambdaQueryWrapper<IngestionPipelineDO>()
                .eq(IngestionPipelineDO::getDeleted, 0)
                .like(StringUtils.hasText(keyword), IngestionPipelineDO::getName, keyword)
                .orderByDesc(IngestionPipelineDO::getUpdateTime);
        IPage<IngestionPipelineDO> result = pipelineMapper.selectPage(mpPage, qw);
        Page<IngestionPipelineVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(result.getRecords().stream()
                .map(each -> toVO(each, fetchNodes(each.getId()), fetchEdges(each.getId())))
                .toList());
        return voPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));
        pipeline.setDeleted(1);
        pipeline.setUpdatedBy(UserContext.getUsername());
        pipelineMapper.deleteById(pipeline);

        nodeMapper.deleteByPipelineId(pipeline.getId());
        edgeMapper.deleteByPipelineId(pipeline.getId());
    }

    @Override
    public PipelineDefinition getDefinition(String pipelineId) {
        IngestionPipelineDO pipeline = pipelineMapper.selectById(pipelineId);
        Assert.notNull(pipeline, () -> new ClientException("未找到流水线"));

        List<NodeConfig> nodes = fetchNodes(pipeline.getId()).stream()
                .map(this::toNodeConfig)
                .toList();
        List<NodeEdge> edges = fetchEdges(pipeline.getId()).stream()
                .map(this::toNodeEdge)
                .toList();
        return PipelineDefinition.builder()
                .id(String.valueOf(pipeline.getId()))
                .name(pipeline.getName())
                .description(pipeline.getDescription())
                .nodes(nodes)
                .edges(edges)
                .build();
    }

    private void replaceNodes(String pipelineId, List<IngestionPipelineNodeRequest> nodes) {
        if (nodes == null) {
            return;
        }
        nodeMapper.deleteByPipelineId(pipelineId);
        for (IngestionPipelineNodeRequest node : nodes) {
            if (node == null) {
                continue;
            }
            IngestionPipelineNodeDO entity = IngestionPipelineNodeDO.builder()
                    .pipelineId(pipelineId)
                    .nodeId(node.getNodeId())
                    .nodeType(normalizeNodeType(node.getNodeType()))
                    .nextNodeId(node.getNextNodeId())
                    .settingsJson(toJson(node.getSettings()))
                    .conditionJson(toJson(node.getCondition()))
                    .executionPolicyJson(toJson(objectMapper.valueToTree(node.getExecutionPolicy())))
                    .createdBy(UserContext.getUsername())
                    .updatedBy(UserContext.getUsername())
                    .build();
            nodeMapper.insert(entity);
        }
    }

    private void replaceEdges(String pipelineId, List<IngestionPipelineEdgeRequest> edges) {
        if (edges == null) {
            return;
        }
        edgeMapper.deleteByPipelineId(pipelineId);
        for (IngestionPipelineEdgeRequest edge : edges) {
            if (edge == null) {
                continue;
            }
            IngestionPipelineEdgeDO entity = IngestionPipelineEdgeDO.builder()
                    .id(edge.getEdgeId())
                    .pipelineId(pipelineId)
                    .fromNodeId(edge.getFromNodeId())
                    .toNodeId(edge.getToNodeId())
                    .conditionJson(toJson(edge.getCondition()))
                    .priority(edge.getPriority() == null ? 0 : edge.getPriority())
                    .defaultEdge(Boolean.TRUE.equals(edge.getDefaultEdge()))
                    .createdBy(UserContext.getUsername())
                    .updatedBy(UserContext.getUsername())
                    .build();
            edgeMapper.insert(entity);
        }
    }

    private List<IngestionPipelineNodeDO> fetchNodes(String pipelineId) {
        LambdaQueryWrapper<IngestionPipelineNodeDO> qw = new LambdaQueryWrapper<IngestionPipelineNodeDO>()
                .eq(IngestionPipelineNodeDO::getPipelineId, pipelineId)
                .eq(IngestionPipelineNodeDO::getDeleted, 0);
        return nodeMapper.selectList(qw);
    }

    private List<IngestionPipelineEdgeDO> fetchEdges(String pipelineId) {
        LambdaQueryWrapper<IngestionPipelineEdgeDO> qw = new LambdaQueryWrapper<IngestionPipelineEdgeDO>()
                .eq(IngestionPipelineEdgeDO::getPipelineId, pipelineId)
                .eq(IngestionPipelineEdgeDO::getDeleted, 0)
                .orderByDesc(IngestionPipelineEdgeDO::getPriority)
                .orderByAsc(IngestionPipelineEdgeDO::getCreateTime);
        return edgeMapper.selectList(qw);
    }

    private IngestionPipelineVO toVO(IngestionPipelineDO pipeline,
                                     List<IngestionPipelineNodeDO> nodes,
                                     List<IngestionPipelineEdgeDO> edges) {
        IngestionPipelineVO vo = BeanUtil.toBean(pipeline, IngestionPipelineVO.class);
        vo.setNodes(nodes.stream().map(this::toNodeVO).toList());
        vo.setEdges(edges.stream().map(this::toEdgeVO).toList());
        return vo;
    }

    private IngestionPipelineNodeVO toNodeVO(IngestionPipelineNodeDO node) {
        IngestionPipelineNodeVO vo = BeanUtil.toBean(node, IngestionPipelineNodeVO.class);
        vo.setNodeType(normalizeNodeTypeForOutput(node.getNodeType()));
        vo.setSettings(parseJson(node.getSettingsJson()));
        vo.setCondition(parseJson(node.getConditionJson()));
        vo.setExecutionPolicy(parseExecutionPolicy(node.getExecutionPolicyJson()));
        return vo;
    }

    private NodeConfig toNodeConfig(IngestionPipelineNodeDO node) {
        return NodeConfig.builder()
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .settings(parseJson(node.getSettingsJson()))
                .condition(parseJson(node.getConditionJson()))
                .executionPolicy(parseExecutionPolicy(node.getExecutionPolicyJson()))
                .nextNodeId(node.getNextNodeId())
                .build();
    }

    private List<NodeConfig> toNodeConfigs(List<IngestionPipelineNodeRequest> nodes) {
        if (nodes == null) {
            return List.of();
        }
        return nodes.stream().filter(java.util.Objects::nonNull).map(node -> NodeConfig.builder()
                .nodeId(node.getNodeId())
                .nodeType(normalizeNodeType(node.getNodeType()))
                .settings(node.getSettings())
                .condition(node.getCondition())
                .executionPolicy(node.getExecutionPolicy())
                .nextNodeId(node.getNextNodeId())
                .build()).toList();
    }

    private List<NodeEdge> toNodeEdges(List<IngestionPipelineEdgeRequest> edges) {
        if (edges == null) {
            return List.of();
        }
        return edges.stream().filter(java.util.Objects::nonNull).map(edge -> NodeEdge.builder()
                .edgeId(edge.getEdgeId())
                .fromNodeId(edge.getFromNodeId())
                .toNodeId(edge.getToNodeId())
                .condition(edge.getCondition())
                .priority(edge.getPriority() == null ? 0 : edge.getPriority())
                .defaultEdge(Boolean.TRUE.equals(edge.getDefaultEdge()))
                .build()).toList();
    }

    private NodeEdge toNodeEdge(IngestionPipelineEdgeDO edge) {
        return NodeEdge.builder()
                .edgeId(edge.getId())
                .fromNodeId(edge.getFromNodeId())
                .toNodeId(edge.getToNodeId())
                .condition(parseJson(edge.getConditionJson()))
                .priority(edge.getPriority() == null ? 0 : edge.getPriority())
                .defaultEdge(Boolean.TRUE.equals(edge.getDefaultEdge()))
                .build();
    }

    private IngestionPipelineEdgeVO toEdgeVO(IngestionPipelineEdgeDO edge) {
        IngestionPipelineEdgeVO vo = new IngestionPipelineEdgeVO();
        vo.setEdgeId(edge.getId());
        vo.setFromNodeId(edge.getFromNodeId());
        vo.setToNodeId(edge.getToNodeId());
        vo.setCondition(parseJson(edge.getConditionJson()));
        vo.setPriority(edge.getPriority());
        vo.setDefaultEdge(edge.getDefaultEdge());
        return vo;
    }

    private void validateDefinition(String pipelineId,
                                    String name,
                                    String description,
                                    List<NodeConfig> nodes,
                                    List<NodeEdge> edges) {
        PipelineGraph.of(PipelineDefinition.builder()
                .id(pipelineId)
                .name(name)
                .description(description)
                .nodes(nodes)
                .edges(edges)
                .build());
    }

    private String toJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private JsonNode parseJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeExecutionPolicy parseExecutionPolicy(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, com.nageoffer.ai.ragent.ingestion.domain.pipeline.NodeExecutionPolicy.class);
        } catch (Exception e) {
            throw new ClientException("Invalid node execution policy");
        }
    }

    private String normalizeNodeType(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            throw new ClientException("未知节点类型: " + nodeType);
        }
    }

    private String normalizeNodeTypeForOutput(String nodeType) {
        if (!StringUtils.hasText(nodeType)) {
            return nodeType;
        }
        try {
            return IngestionNodeType.fromValue(nodeType).getValue();
        } catch (IllegalArgumentException ex) {
            return nodeType;
        }
    }
}
