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

package com.nageoffer.ai.ragent.multimodal.parser.pdf;

/** Versionable thresholds used by AUTO page routing. */
public record PdfTextQualityThresholds(
        String policyVersion,
        int minimumEffectiveCharacters,
        double minimumValidCharacterRatio,
        double maximumMojibakeRatio,
        double minimumVisualCoverageRatio) {

    public PdfTextQualityThresholds {
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion must not be blank");
        }
        if (minimumEffectiveCharacters < 0) {
            throw new IllegalArgumentException("minimumEffectiveCharacters must not be negative");
        }
        requireRatio(minimumValidCharacterRatio, "minimumValidCharacterRatio");
        requireRatio(maximumMojibakeRatio, "maximumMojibakeRatio");
        requireRatio(minimumVisualCoverageRatio, "minimumVisualCoverageRatio");
    }

    private static void requireRatio(double value, String name) {
        if (Double.isNaN(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1");
        }
    }
}
