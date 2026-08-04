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

import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/** Configuration-backed role weights for direct hyperedge matches. */
@Data
@Component
@RequiredArgsConstructor
@ConfigurationProperties(prefix = "ragent.hypergraph.entity-weights")
public class ConfigurableHyperEdgeMatchScorer implements HyperEdgeMatchScorer {

    private final IndustrialEntityNormalizer entityNormalizer;
    private double equipment = 1.5;
    private double fault = 1.3;
    private double parameter = 1.2;
    private double condition = 1.0;
    private double sopDoc = 1.0;
    private double extended = 1.0;

    @Override
    public double score(HyperEdge edge, Set<String> normalizedQueryEntities) {
        Set<String> counted = new LinkedHashSet<>();
        return score(edge.getEquipment(), equipment, normalizedQueryEntities, counted)
                + score(edge.getFault(), fault, normalizedQueryEntities, counted)
                + score(edge.getParameter(), parameter, normalizedQueryEntities, counted)
                + score(edge.getCondition(), condition, normalizedQueryEntities, counted)
                + score(edge.getSopDoc(), sopDoc, normalizedQueryEntities, counted)
                + scoreExtended(edge, normalizedQueryEntities, counted);
    }

    private double scoreExtended(HyperEdge edge, Set<String> queryEntities, Set<String> counted) {
        if (edge.getExtendedEntities() == null) {
            return 0;
        }
        return edge.getExtendedEntities().stream()
                .mapToDouble(entity -> score(entity.value(), extended, queryEntities, counted))
                .sum();
    }

    private double score(String entity, double weight, Set<String> queryEntities, Set<String> counted) {
        String canonical = entityNormalizer.normalize(entity);
        return canonical != null && queryEntities.contains(canonical) && counted.add(canonical) ? weight : 0;
    }
}
