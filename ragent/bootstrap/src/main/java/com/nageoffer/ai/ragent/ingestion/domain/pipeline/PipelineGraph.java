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

import com.fasterxml.jackson.databind.JsonNode;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Validated execution view of an ingestion pipeline.
 *
 * <p>This module is the sole place that understands both the legacy single-output
 * {@link NodeConfig#getNextNodeId()} configuration and explicit graph edges. Explicit outgoing
 * edges take precedence over a legacy next node from the same source node.</p>
 */
public final class PipelineGraph {

    private final Map<String, NodeConfig> nodes;
    private final Map<String, List<NodeEdge>> outgoingEdges;
    private final String startNodeId;

    private PipelineGraph(Map<String, NodeConfig> nodes,
                          Map<String, List<NodeEdge>> outgoingEdges,
                          String startNodeId) {
        this.nodes = nodes;
        this.outgoingEdges = outgoingEdges;
        this.startNodeId = startNodeId;
    }

    public static PipelineGraph of(PipelineDefinition definition) {
        if (definition == null || definition.getNodes() == null || definition.getNodes().isEmpty()) {
            throw new ClientException("Pipeline must contain at least one node");
        }

        Map<String, NodeConfig> nodes = indexNodes(definition.getNodes());
        List<NodeEdge> effectiveEdges = buildEffectiveEdges(nodes, definition.getEdges());
        Map<String, List<NodeEdge>> outgoingEdges = groupAndValidateEdges(nodes, effectiveEdges);
        String startNodeId = validateTopology(nodes, outgoingEdges);
        return new PipelineGraph(nodes, outgoingEdges, startNodeId);
    }

    public String startNodeId() {
        return startNodeId;
    }

    public NodeConfig node(String nodeId) {
        return nodes.get(nodeId);
    }

    public int size() {
        return nodes.size();
    }

    /**
     * Returns validated, priority-sorted outgoing edges for a routing policy.
     */
    public List<NodeEdge> outgoingEdges(String nodeId) {
        return List.copyOf(outgoingEdges.getOrDefault(nodeId, List.of()));
    }

    private static Map<String, NodeConfig> indexNodes(Collection<NodeConfig> nodeConfigs) {
        Map<String, NodeConfig> nodes = new LinkedHashMap<>();
        for (NodeConfig node : nodeConfigs) {
            if (node == null || !StringUtils.hasText(node.getNodeId())) {
                throw new ClientException("Pipeline nodeId must not be blank");
            }
            if (nodes.putIfAbsent(node.getNodeId(), node) != null) {
                throw new ClientException("Duplicate pipeline nodeId: " + node.getNodeId());
            }
            validateExecutionPolicy(node);
        }
        return nodes;
    }

    private static void validateExecutionPolicy(NodeConfig node) {
        NodeExecutionPolicy policy = node.getExecutionPolicy();
        if (policy == null) {
            return;
        }
        int maxAttempts = policy.effectiveMaxAttempts();
        long backoffMs = policy.effectiveRetryBackoffMs();
        if (maxAttempts < 1 || maxAttempts > NodeExecutionPolicy.MAX_ALLOWED_ATTEMPTS) {
            throw new ClientException("Node " + node.getNodeId() + " maxAttempts must be between 1 and "
                    + NodeExecutionPolicy.MAX_ALLOWED_ATTEMPTS);
        }
        if (backoffMs < 0 || backoffMs > NodeExecutionPolicy.MAX_ALLOWED_BACKOFF_MS) {
            throw new ClientException("Node " + node.getNodeId() + " retryBackoffMs is out of range");
        }
        if (maxAttempts > 1 && !isRetrySafe(node.getNodeType())) {
            throw new ClientException("Node " + node.getNodeId()
                    + " cannot retry until its side effects are idempotent");
        }
    }

    private static boolean isRetrySafe(String nodeType) {
        return "fetcher".equalsIgnoreCase(nodeType)
                || "parser".equalsIgnoreCase(nodeType)
                || "multimodal_parse".equalsIgnoreCase(nodeType);
    }

    private static List<NodeEdge> buildEffectiveEdges(Map<String, NodeConfig> nodes,
                                                       List<NodeEdge> explicitEdges) {
        List<NodeEdge> result = explicitEdges == null
                ? new ArrayList<>()
                : new ArrayList<>(explicitEdges);
        Set<String> explicitSources = result.stream()
                .filter(edge -> edge != null)
                .map(NodeEdge::getFromNodeId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());

        for (NodeConfig node : nodes.values()) {
            if (StringUtils.hasText(node.getNextNodeId()) && !explicitSources.contains(node.getNodeId())) {
                result.add(NodeEdge.builder()
                        .edgeId("legacy:" + node.getNodeId() + "->" + node.getNextNodeId())
                        .fromNodeId(node.getNodeId())
                        .toNodeId(node.getNextNodeId())
                        .defaultEdge(true)
                        .build());
            }
        }
        return result;
    }

    private static Map<String, List<NodeEdge>> groupAndValidateEdges(Map<String, NodeConfig> nodes,
                                                                       List<NodeEdge> edges) {
        Map<String, List<NodeEdge>> outgoing = new HashMap<>();
        Set<String> edgeIds = new HashSet<>();
        for (NodeEdge edge : edges) {
            if (edge == null || !StringUtils.hasText(edge.getFromNodeId()) || !StringUtils.hasText(edge.getToNodeId())) {
                throw new ClientException("Pipeline edge endpoints must not be blank");
            }
            if (!nodes.containsKey(edge.getFromNodeId()) || !nodes.containsKey(edge.getToNodeId())) {
                throw new ClientException("Pipeline edge references a node that does not exist: "
                        + edge.getFromNodeId() + " -> " + edge.getToNodeId());
            }
            if (StringUtils.hasText(edge.getEdgeId()) && !edgeIds.add(edge.getEdgeId())) {
                throw new ClientException("Duplicate pipeline edgeId: " + edge.getEdgeId());
            }
            if (edge.isDefaultEdge() && edge.getCondition() != null && !edge.getCondition().isNull()) {
                throw new ClientException("Default pipeline edge cannot declare a condition: " + edge.getEdgeId());
            }
            if (!edge.isDefaultEdge() && (edge.getCondition() == null || edge.getCondition().isNull())) {
                throw new ClientException("Conditional pipeline edge must declare a condition: " + edge.getEdgeId());
            }
            validateCondition(edge.getCondition(), edge.getEdgeId());
            outgoing.computeIfAbsent(edge.getFromNodeId(), ignored -> new ArrayList<>()).add(edge);
        }

        for (Map.Entry<String, List<NodeEdge>> entry : outgoing.entrySet()) {
            List<NodeEdge> nodeEdges = entry.getValue();
            long defaultEdgeCount = nodeEdges.stream().filter(NodeEdge::isDefaultEdge).count();
            if (defaultEdgeCount > 1) {
                throw new ClientException("Node " + entry.getKey() + " has more than one default edge");
            }
            Set<Integer> priorities = new HashSet<>();
            for (NodeEdge edge : nodeEdges) {
                if (!edge.isDefaultEdge() && !priorities.add(edge.getPriority())) {
                    throw new ClientException("Node " + entry.getKey()
                            + " has conditional edges with the same priority: " + edge.getPriority());
                }
            }
            nodeEdges.sort(Comparator.comparing(NodeEdge::isDefaultEdge)
                    .thenComparing(NodeEdge::getPriority, Comparator.reverseOrder()));
        }
        return outgoing;
    }

    private static void validateCondition(JsonNode condition, String edgeId) {
        if (condition == null || condition.isNull() || !condition.isObject()) {
            return;
        }
        if (condition.has("all") || condition.has("any")) {
            JsonNode children = condition.has("all") ? condition.get("all") : condition.get("any");
            if (children != null && children.isArray()) {
                for (JsonNode child : children) {
                    validateCondition(child, edgeId);
                }
            }
        }
        if (condition.has("not")) {
            validateCondition(condition.get("not"), edgeId);
        }
        if (!"regex".equalsIgnoreCase(condition.path("operator").asText())) {
            return;
        }
        JsonNode value = condition.get("value");
        if (value == null || value.isNull()) {
            return;
        }
        try {
            Pattern.compile(value.asText());
        } catch (PatternSyntaxException ex) {
            throw new ClientException("Invalid regex condition on pipeline edge " + edgeId + ": " + ex.getDescription());
        }
    }

    private static String validateTopology(Map<String, NodeConfig> nodes,
                                           Map<String, List<NodeEdge>> outgoingEdges) {
        Map<String, Integer> indegree = new HashMap<>();
        nodes.keySet().forEach(nodeId -> indegree.put(nodeId, 0));
        for (List<NodeEdge> edges : outgoingEdges.values()) {
            for (NodeEdge edge : edges) {
                indegree.compute(edge.getToNodeId(), (ignored, value) -> value + 1);
            }
        }
        detectCycle(nodes.keySet(), outgoingEdges);
        List<String> startNodes = indegree.entrySet().stream()
                .filter(entry -> entry.getValue() == 0)
                .map(Map.Entry::getKey)
                .toList();
        if (startNodes.size() != 1) {
            throw new ClientException("Pipeline must have exactly one start node, found: " + startNodes.size());
        }
        if (outgoingEdges.values().stream().flatMap(Collection::stream).count() > 0
                && outgoingEdges.size() == nodes.size()) {
            throw new ClientException("Pipeline must contain at least one terminal node");
        }

        validateReachability(startNodes.get(0), nodes.keySet(), outgoingEdges);
        return startNodes.get(0);
    }

    private static void detectCycle(Set<String> nodeIds, Map<String, List<NodeEdge>> outgoingEdges) {
        Map<String, VisitState> states = new HashMap<>();
        for (String nodeId : nodeIds) {
            if (states.getOrDefault(nodeId, VisitState.NEW) == VisitState.NEW) {
                detectCycleDfs(nodeId, outgoingEdges, states);
            }
        }
    }

    private static void detectCycleDfs(String nodeId,
                                       Map<String, List<NodeEdge>> outgoingEdges,
                                       Map<String, VisitState> states) {
        states.put(nodeId, VisitState.VISITING);
        for (NodeEdge edge : outgoingEdges.getOrDefault(nodeId, List.of())) {
            VisitState targetState = states.getOrDefault(edge.getToNodeId(), VisitState.NEW);
            if (targetState == VisitState.VISITING) {
                throw new ClientException("Pipeline contains a cycle: " + nodeId + " -> " + edge.getToNodeId());
            }
            if (targetState == VisitState.NEW) {
                detectCycleDfs(edge.getToNodeId(), outgoingEdges, states);
            }
        }
        states.put(nodeId, VisitState.DONE);
    }

    private static void validateReachability(String startNodeId,
                                             Set<String> nodeIds,
                                             Map<String, List<NodeEdge>> outgoingEdges) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(startNodeId);
        while (!queue.isEmpty()) {
            String nodeId = queue.removeFirst();
            if (!visited.add(nodeId)) {
                continue;
            }
            for (NodeEdge edge : outgoingEdges.getOrDefault(nodeId, List.of())) {
                queue.addLast(edge.getToNodeId());
            }
        }
        if (visited.size() != nodeIds.size()) {
            Set<String> unreachable = new HashSet<>(nodeIds);
            unreachable.removeAll(visited);
            throw new ClientException("Pipeline contains unreachable nodes: " + unreachable);
        }
    }

    private enum VisitState {
        NEW,
        VISITING,
        DONE
    }
}
