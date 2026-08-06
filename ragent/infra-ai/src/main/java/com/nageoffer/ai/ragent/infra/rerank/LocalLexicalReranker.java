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

package com.nageoffer.ai.ragent.infra.rerank;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic fallback reranker used when no remote rerank model is
 * available. The public rerank interface stays small while the local scoring
 * details remain isolated from the model-routing adapter.
 */
final class LocalLexicalReranker {

    private static final double LEXICAL_WEIGHT = 0.85D;
    private static final double PRIOR_WEIGHT = 0.15D;

    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topN) {
        if (candidates == null || candidates.isEmpty() || topN <= 0) {
            return List.of();
        }

        Set<String> queryBigrams = bigrams(query);
        List<RankedChunk> ranked = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            RetrievedChunk candidate = candidates.get(index);
            double lexicalScore = dice(queryBigrams, bigrams(candidate.getText()));
            double priorScore = boundedScore(candidate.getScore());
            double combinedScore = LEXICAL_WEIGHT * lexicalScore + PRIOR_WEIGHT * priorScore;
            ranked.add(new RankedChunk(index, copyWithFallbackScore(candidate, lexicalScore, combinedScore), combinedScore));
        }

        return ranked.stream()
                .sorted(Comparator.comparingDouble(RankedChunk::score).reversed()
                        .thenComparingInt(RankedChunk::originalIndex))
                .limit(topN)
                .map(RankedChunk::chunk)
                .toList();
    }

    private static RetrievedChunk copyWithFallbackScore(RetrievedChunk source,
                                                         double lexicalScore,
                                                         double combinedScore) {
        Map<String, Object> metadata = source.getMetadata() == null
                ? new HashMap<>()
                : new HashMap<>(source.getMetadata());
        metadata.put("rerankMode", "local_lexical_fallback");
        metadata.put("fallbackLexicalScore", lexicalScore);
        return RetrievedChunk.builder()
                .id(source.getId())
                .text(source.getText())
                .score((float) combinedScore)
                .metadata(metadata)
                .build();
    }

    private static double boundedScore(Float score) {
        if (score == null) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, score));
    }

    private static double dice(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0D;
        }
        int intersection = 0;
        for (String term : left) {
            if (right.contains(term)) {
                intersection++;
            }
        }
        return 2D * intersection / (left.size() + right.size());
    }

    private static Set<String> bigrams(String text) {
        String normalized = normalize(text);
        if (normalized.isEmpty()) {
            return Set.of();
        }
        int[] codePoints = normalized.codePoints().toArray();
        Set<String> terms = new LinkedHashSet<>();
        if (codePoints.length == 1) {
            terms.add(new String(codePoints, 0, 1));
            return terms;
        }
        for (int index = 0; index < codePoints.length - 1; index++) {
            terms.add(new String(codePoints, index, 2));
        }
        return terms;
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    private record RankedChunk(int originalIndex, RetrievedChunk chunk, double score) {
    }
}
