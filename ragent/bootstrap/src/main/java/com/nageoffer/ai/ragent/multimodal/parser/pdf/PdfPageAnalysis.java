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

import java.util.Objects;

/** PDFBox observations required to decide how one page should be parsed. */
public record PdfPageAnalysis(
        int pageNumber,
        String nativeText,
        int nativeCharacterCount,
        double visualCoverageRatio) {

    public PdfPageAnalysis {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must start at 1");
        }
        nativeText = Objects.requireNonNullElse(nativeText, "");
        if (nativeCharacterCount < 0) {
            throw new IllegalArgumentException("nativeCharacterCount must not be negative");
        }
        if (Double.isNaN(visualCoverageRatio)
                || visualCoverageRatio < 0.0
                || visualCoverageRatio > 1.0) {
            throw new IllegalArgumentException("visualCoverageRatio must be between 0 and 1");
        }
    }
}
