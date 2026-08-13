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

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** Ordered page-level result for one PDF document. */
public record DocumentParseResult(
        String sourceFile,
        PdfParseMode mode,
        String policyVersion,
        long durationMillis,
        List<PageParseResult> pages) {

    public DocumentParseResult {
        sourceFile = requireText(sourceFile, "sourceFile");
        mode = Objects.requireNonNull(mode, "mode");
        policyVersion = requireText(policyVersion, "policyVersion");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must not be negative");
        }
        pages = List.copyOf(Objects.requireNonNull(pages, "pages"));
        int previousPage = 0;
        for (PageParseResult page : pages) {
            if (page.pageNumber() <= previousPage) {
                throw new IllegalArgumentException("pages must use unique ascending page numbers");
            }
            previousPage = page.pageNumber();
        }
    }

    public String mergedText() {
        return pages.stream()
                .filter(page -> page.status() == PageParseStatus.COMPLETED)
                .map(PageParseResult::text)
                .collect(Collectors.joining("\n\n"));
    }

    public List<Integer> ocrPageNumbers() {
        return pages.stream()
                .filter(page -> page.strategy() == PageParseStrategy.OCR)
                .map(PageParseResult::pageNumber)
                .toList();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
