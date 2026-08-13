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

/** Selects a page parser without performing PDF extraction or OCR. */
public final class PdfPageRoutingPolicy {

    private final PdfTextQualityThresholds thresholds;

    public PdfPageRoutingPolicy(PdfTextQualityThresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    public String policyVersion() {
        return thresholds.policyVersion();
    }

    public PdfPageDecision decide(PdfPageAnalysis page, PdfParseMode mode) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(mode, "mode");

        TextQuality quality = analyze(page.nativeText());
        if (mode == PdfParseMode.PDFBOX) {
            return decision(page, PageParseStrategy.PDFBOX, PageParseReason.FORCED_PDFBOX, quality);
        }
        if (mode == PdfParseMode.OCR) {
            return decision(page, PageParseStrategy.OCR, PageParseReason.FORCED_OCR, quality);
        }
        if (quality.effectiveCharacters() == 0
                && page.visualCoverageRatio() >= thresholds.minimumVisualCoverageRatio()) {
            return decision(
                    page,
                    PageParseStrategy.OCR,
                    PageParseReason.VISUAL_CONTENT_WITHOUT_TEXT,
                    quality);
        }
        if (quality.effectiveCharacters() == 0) {
            return decision(page, PageParseStrategy.EMPTY, PageParseReason.PAGE_EMPTY, quality);
        }
        if (quality.validRatio() < thresholds.minimumValidCharacterRatio()
                || quality.mojibakeRatio() >= thresholds.maximumMojibakeRatio()) {
            return decision(
                    page,
                    PageParseStrategy.OCR,
                    PageParseReason.NATIVE_TEXT_LOW_QUALITY,
                    quality);
        }
        if (quality.effectiveCharacters() < thresholds.minimumEffectiveCharacters()) {
            return decision(
                    page,
                    PageParseStrategy.OCR,
                    PageParseReason.NATIVE_TEXT_INSUFFICIENT,
                    quality);
        }
        return decision(page, PageParseStrategy.PDFBOX, PageParseReason.NATIVE_TEXT_USABLE, quality);
    }

    private PdfPageDecision decision(
            PdfPageAnalysis page,
            PageParseStrategy strategy,
            PageParseReason reason,
            TextQuality quality) {
        return new PdfPageDecision(
                page.pageNumber(), strategy, reason, quality.effectiveCharacters(), quality.validRatio());
    }

    private TextQuality analyze(String text) {
        int effective = 0;
        int valid = 0;
        int mojibakeMarkers = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                continue;
            }
            effective++;
            if (isMojibakeMarker(codePoint)) {
                mojibakeMarkers++;
            }
            if (Character.isLetterOrDigit(codePoint) || isUsefulPunctuation(codePoint)) {
                valid++;
            }
        }
        return new TextQuality(
                effective,
                effective == 0 ? 0.0 : (double) valid / effective,
                effective == 0 ? 0.0 : (double) mojibakeMarkers / effective);
    }

    private boolean isMojibakeMarker(int codePoint) {
        return codePoint == '\u00c2'
                || codePoint == '\u00c3'
                || codePoint == '\u00e2'
                || codePoint == '\u20ac'
                || codePoint == '\u2122'
                || codePoint == '\ufffd';
    }

    private boolean isUsefulPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private record TextQuality(int effectiveCharacters, double validRatio, double mojibakeRatio) {}
}
