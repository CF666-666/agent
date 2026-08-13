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

package com.nageoffer.ai.ragent.rag.core.hypergraph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * 工业超图引擎实现
 * <p>
 * 核心数据结构：
 * <ul>
 *   <li>倒排索引 {@code Map<实体值, Set<超边下标>>} — 子图匹配主引擎，O(k) 复杂度</li>
 *   <li>超边列表 {@code List<HyperEdge>} — 下标→超边映射</li>
 *   <li>{@link ReadWriteLock} — 写入口（addHyperedges）排他，读入口（matchSubgraph）并发</li>
 * </ul>
 * <p>
 * 调用链：
 * <pre>
 *   documentText → extractHyperedges() → HyperEdgeExtractor
 *                → addHyperedges() → 倒排索引
 *   queryEntities → matchSubgraph() → 倒排索引命中 → 命中数排序 → SubgraphMatchResult
 * </pre>
 *
 * @see IndustrialHyperGraph  超图引擎接口
 * @see HyperEdgeExtractor    N 元组抽取器
 * @see HyperEdge             超边数据结构
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndustrialHyperGraphImpl implements IndustrialHyperGraph {

    private static final int MAX_RELATION_HOPS = 2;
    private static final int MAX_PATH_SEEDS = 20;

    private final HyperEdgeExtractor extractor;
    private final IndustrialEntityNormalizer entityNormalizer;
    private final HyperEdgeMatchScorer matchScorer;

    // ==================== 存储结构 ====================

    /** 超边列表，下标与倒排索引对齐 */
    private final List<HyperEdge> hyperEdges = new ArrayList<>();

    /** 倒排索引：实体值 → 包含该实体的超边下标集合 */
    private final Map<String, Set<Integer>> entityToEdgeIdx = new HashMap<>();

    /** Immutable query-mention snapshot rebuilt atomically with the inverted index. */
    private volatile List<EntityMention> entityMentions = List.of();

    /** 读写锁：写操作排他，读操作可并发 */
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** 单参数接口方法的默认来源标识 */
    private static final String DEFAULT_SOURCE = "inline";

    // ==================== 抽取 ====================

    @Override
    public List<HyperEdge> extractHyperedges(String documentText) {
        return extractHyperedges(documentText, DEFAULT_SOURCE);
    }

    /**
     * 带来源路径的抽取（供 Pipeline 调用，填充 sourceDocument 字段）
     *
     * @param documentText   文档文本
     * @param sourceDocument 来源文档路径
     * @return 抽取出的超边列表
     */
    public List<HyperEdge> extractHyperedges(String documentText, String sourceDocument) {
        if (documentText == null || documentText.isBlank()) {
            return Collections.emptyList();
        }
        return extractor.extractHyperedges(documentText, sourceDocument);
    }

    /**
     * 便捷方法：抽取文档 → 直接索引（Demo 数据集一键摄入）
     *
     * @param documentText   文档文本
     * @param sourceDocument 来源文档路径
     */
    public void ingestDocument(String documentText, String sourceDocument) {
        List<HyperEdge> edges = extractHyperedges(documentText, sourceDocument);
        if (!edges.isEmpty()) {
            addHyperedges(edges);
        }
    }

    // ==================== 索引 ====================

    @Override
    public void addHyperedges(List<HyperEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return;
        }

        lock.writeLock().lock();
        try {
            int skipped = 0;
            for (HyperEdge edge : edges) {
                Set<String> entities = indexableEntityValues(edge);
                // 修法 A：无实体超边完全不加入（无检索价值，占内存无意义）
                if (entities.isEmpty()) {
                    log.warn("跳过无实体超边: edgeId={}, source={}", edge.getEdgeId(), edge.getSourceDocument());
                    skipped++;
                    continue;
                }

                int edgeIdx = hyperEdges.size();
                hyperEdges.add(edge);

                // 倒排索引
                for (String entity : entities) {
                    entityToEdgeIdx.computeIfAbsent(entity, k -> new HashSet<>()).add(edgeIdx);
                }
            }

            rebuildMentionSnapshot();

            log.info("超边索引完成。新增={}, 跳过空超边={}, 总超边数={}, 总实体数={}",
                    edges.size() - skipped, skipped, hyperEdges.size(), entityToEdgeIdx.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void replaceDocumentHyperedges(String sourceDocument, List<HyperEdge> edges) {
        lock.writeLock().lock();
        try {
            hyperEdges.removeIf(edge -> Objects.equals(sourceDocument, edge.getSourceDocument()));
            if (edges != null) {
                for (HyperEdge edge : edges) {
                    if (!indexableEntityValues(edge).isEmpty()) {
                        hyperEdges.add(edge);
                    }
                }
            }
            rebuildEntityIndex();
            log.info("Replaced hyperedges for document {}, total edges={}", sourceDocument, hyperEdges.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void rebuildEntityIndex() {
        entityToEdgeIdx.clear();
        for (int edgeIndex = 0; edgeIndex < hyperEdges.size(); edgeIndex++) {
            for (String entity : indexableEntityValues(hyperEdges.get(edgeIndex))) {
                entityToEdgeIdx.computeIfAbsent(entity, ignored -> new HashSet<>()).add(edgeIndex);
            }
        }
        rebuildMentionSnapshot();
    }

    private void rebuildMentionSnapshot() {
        entityMentions = entityNormalizer.mentionForms(entityToEdgeIdx.keySet()).entrySet().stream()
                .filter(entry -> isDistinctiveMention(entry.getKey()))
                .map(entry -> new EntityMention(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt((EntityMention mention) -> mention.text().length()).reversed()
                        .thenComparing(EntityMention::text))
                .toList();
    }

    private boolean isDistinctiveMention(String mention) {
        if (mention == null) {
            return false;
        }
        int codePoints = mention.codePointCount(0, mention.length());
        return codePoints >= 3
                || mention.codePoints().anyMatch(Character::isDigit)
                || (codePoints == 2 && mention.codePoints().allMatch(this::isCjk));
    }

    private boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN;
    }

    private Set<String> indexableEntityValues(HyperEdge edge) {
        return entityNormalizer.normalizeAll(edge.allEntityValues());
    }

    // ==================== 检索 ====================

    @Override
    public List<SubgraphMatchResult> matchSubgraph(Set<String> queryEntities, int maxEdges) {
        if (queryEntities == null || queryEntities.isEmpty()) {
            return Collections.emptyList();
        }
        if (maxEdges <= 0) {
            log.warn("matchSubgraph 收到非法 maxEdges={}，返回空列表", maxEdges);
            return Collections.emptyList();
        }

        Set<String> normalizedQueries = entityNormalizer.normalizeAll(queryEntities);
        lock.readLock().lock();
        try {
            // 统计每条超边的命中数
            Map<Integer, Integer> hitCounts = new HashMap<>();
            for (String entity : normalizedQueries) {
                Set<Integer> matchedEdges = entityToEdgeIdx.get(entity);
                if (matchedEdges != null) {
                    for (int edgeIdx : matchedEdges) {
                        hitCounts.merge(edgeIdx, 1, Integer::sum);
                    }
                }
            }

            if (hitCounts.isEmpty()) {
                return Collections.emptyList();
            }

            // 按命中数降序 → Top-N
            return hitCounts.entrySet().stream()
                    .sorted(Comparator.comparingDouble((Map.Entry<Integer, Integer> entry) ->
                                    matchScorer.score(hyperEdges.get(entry.getKey()), normalizedQueries)).reversed()
                            .thenComparing(Map.Entry::getKey))
                    .limit(maxEdges)
                    .map(entry -> new SubgraphMatchResult(
                            hyperEdges.get(entry.getKey()),
                            entry.getValue()))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<RelationPath> findRelationPaths(Set<String> queryEntities, int maxHops, int maxPaths) {
        return findRelationPathsInternal(queryEntities, maxHops, maxPaths, () -> true);
    }

    private List<RelationPath> findRelationPathsInternal(
            Set<String> queryEntities,
            int maxHops,
            int maxPaths,
            java.util.function.BooleanSupplier active) {
        ensureActive(active, "relation path lookup was cancelled");
        if (maxHops < 1 || maxHops > MAX_RELATION_HOPS || maxPaths <= 0) {
            return Collections.emptyList();
        }
        Set<String> normalizedQueries = entityNormalizer.normalizeAll(queryEntities);
        if (normalizedQueries.isEmpty()) {
            return Collections.emptyList();
        }

        lock.readLock().lock();
        try {
            Map<Integer, Integer> hitCounts = matchedEdgeCounts(normalizedQueries, active);
            if (hitCounts.isEmpty()) {
                return Collections.emptyList();
            }
            List<Map.Entry<Integer, Integer>> seeds = hitCounts.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry::getKey))
                    .limit(MAX_PATH_SEEDS)
                    .toList();
            Map<String, RelationPath> paths = new LinkedHashMap<>();
            for (Map.Entry<Integer, Integer> seed : seeds) {
                ensureActive(active, "relation path lookup was cancelled");
                paths.put("edge:" + seed.getKey(), new RelationPath(
                        List.of(hyperEdges.get(seed.getKey())), List.of(), seed.getValue()));
            }
            if (maxHops == 2) {
                addTwoHopPaths(normalizedQueries, seeds, paths, active);
            }
            return paths.values().stream()
                    .sorted(Comparator.comparingInt(RelationPath::score).reversed()
                            .thenComparing(Comparator.comparingInt(RelationPath::hopCount).reversed()))
                    .limit(maxPaths)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Set<String> findMentionedEntities(String query, java.util.function.BooleanSupplier active) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }
        ensureActive(active, "local entity matching was cancelled");
        Set<String> mentioned = new LinkedHashSet<>();
        for (EntityMention mention : entityMentions) {
            ensureActive(active, "local entity matching was cancelled");
            if (containsMention(query, mention.text())) {
                mentioned.add(mention.canonical());
            }
        }
        ensureActive(active, "local entity matching was cancelled");
        return mentioned;
    }

    private boolean containsMention(String query, String mention) {
        int fromIndex = 0;
        while (fromIndex <= query.length() - mention.length()) {
            int index = query.indexOf(mention, fromIndex);
            if (index < 0) {
                return false;
            }
            int end = index + mention.length();
            if ((!isAsciiWord(mention)
                    || ((!hasAsciiWordCharacterBefore(query, index))
                    && !hasAsciiWordCharacterAfter(query, end)))) {
                return true;
            }
            fromIndex = index + 1;
        }
        return false;
    }

    private boolean isAsciiWord(String value) {
        return value.chars().allMatch(character -> character < 128
                && (Character.isLetterOrDigit(character) || character == '-' || character == '_'));
    }

    private boolean hasAsciiWordCharacterBefore(String value, int index) {
        return index > 0 && isAsciiWordCharacter(value.charAt(index - 1));
    }

    private boolean hasAsciiWordCharacterAfter(String value, int index) {
        return index < value.length() && isAsciiWordCharacter(value.charAt(index));
    }

    private boolean isAsciiWordCharacter(char character) {
        return character < 128
                && (Character.isLetterOrDigit(character) || character == '-' || character == '_');
    }

    private void ensureActive(java.util.function.BooleanSupplier active, String message) {
        if (Thread.currentThread().isInterrupted() || active == null || !active.getAsBoolean()) {
            throw new java.util.concurrent.CancellationException(message);
        }
    }

    private record EntityMention(String text, String canonical) {
    }

    @Override
    public List<RelationPath> findRelationPaths(
            Set<String> queryEntities,
            int maxHops,
            int maxPaths,
            java.util.function.BooleanSupplier active) {
        ensureActive(active, "relation path lookup was cancelled");
        List<RelationPath> result = findRelationPathsInternal(queryEntities, maxHops, maxPaths, active);
        ensureActive(active, "relation path lookup was cancelled");
        return result;
    }

    private Map<Integer, Integer> matchedEdgeCounts(Set<String> queryEntities) {
        return matchedEdgeCounts(queryEntities, () -> true);
    }

    private Map<Integer, Integer> matchedEdgeCounts(
            Set<String> queryEntities,
            java.util.function.BooleanSupplier active) {
        Map<Integer, Integer> hitCounts = new HashMap<>();
        for (String entity : queryEntities) {
            ensureActive(active, "relation path lookup was cancelled");
            Set<Integer> matchedEdges = entityToEdgeIdx.get(entity);
            if (matchedEdges != null) {
                for (int edgeIdx : matchedEdges) {
                    ensureActive(active, "relation path lookup was cancelled");
                    hitCounts.merge(edgeIdx, 1, Integer::sum);
                }
            }
        }
        return hitCounts;
    }

    private void addTwoHopPaths(Set<String> queryEntities,
                                List<Map.Entry<Integer, Integer>> seeds,
                                Map<String, RelationPath> paths,
                                java.util.function.BooleanSupplier active) {
        for (Map.Entry<Integer, Integer> seed : seeds) {
            ensureActive(active, "relation path lookup was cancelled");
            int firstEdgeIndex = seed.getKey();
            for (String bridgeEntity : indexableEntityValues(hyperEdges.get(firstEdgeIndex))) {
                ensureActive(active, "relation path lookup was cancelled");
                if (queryEntities.contains(bridgeEntity)) {
                    continue;
                }
                Set<Integer> connectedEdges = entityToEdgeIdx.getOrDefault(bridgeEntity, Set.of());
                for (int secondEdgeIndex : connectedEdges) {
                    ensureActive(active, "relation path lookup was cancelled");
                    if (secondEdgeIndex == firstEdgeIndex) {
                        continue;
                    }
                    String pathKey = "path:" + Math.min(firstEdgeIndex, secondEdgeIndex)
                            + ':' + Math.max(firstEdgeIndex, secondEdgeIndex);
                    paths.putIfAbsent(pathKey, new RelationPath(
                            List.of(hyperEdges.get(firstEdgeIndex), hyperEdges.get(secondEdgeIndex)),
                            List.of(bridgeEntity), seed.getValue() + 1));
                }
            }
        }
    }
}
