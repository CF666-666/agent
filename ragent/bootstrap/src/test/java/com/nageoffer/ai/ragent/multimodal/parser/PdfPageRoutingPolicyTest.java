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

package com.nageoffer.ai.ragent.multimodal.parser;

import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseReason;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseStrategy;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageAnalysis;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageDecision;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfPageRoutingPolicy;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfParseMode;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfTextQualityThresholds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PdfPageRoutingPolicyTest {

    private final PdfPageRoutingPolicy policy = new PdfPageRoutingPolicy(
            new PdfTextQualityThresholds("pdf-routing-v1", 20, 0.50, 0.15, 0.02));

    @Test
    void exposesVersionedThresholdConfiguration() {
        assertEquals("pdf-routing-v1", policy.policyVersion());
        assertThrows(
                IllegalArgumentException.class,
                () -> new PdfTextQualityThresholds("", 20, 0.50, 0.15, 0.02));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PdfTextQualityThresholds("pdf-routing-v1", 20, 0.50, 1.01, 0.02));
    }

    @Test
    void autoKeepsNativeTextWhenPageTextIsUsable() {
        PdfPageAnalysis page = new PdfPageAnalysis(
                1,
                "1234567890 abcdefghij",
                21,
                0.12);

        PdfPageDecision decision = policy.decide(page, PdfParseMode.AUTO);

        assertEquals(PageParseStrategy.PDFBOX, decision.strategy());
        assertEquals(PageParseReason.NATIVE_TEXT_USABLE, decision.reason());
        assertEquals(20, decision.effectiveCharacterCount());
    }

    @Test
    void autoRoutesImageOnlyPageToOcr() {
        PdfPageAnalysis page = new PdfPageAnalysis(2, "", 0, 0.80);

        PdfPageDecision decision = policy.decide(page, PdfParseMode.AUTO);

        assertEquals(PageParseStrategy.OCR, decision.strategy());
        assertEquals(PageParseReason.VISUAL_CONTENT_WITHOUT_TEXT, decision.reason());
    }

    @Test
    void autoMarksPageWithoutTextOrVisualContentAsEmpty() {
        PdfPageAnalysis page = new PdfPageAnalysis(3, " \n\t", 3, 0.0);

        PdfPageDecision decision = policy.decide(page, PdfParseMode.AUTO);

        assertEquals(PageParseStrategy.EMPTY, decision.strategy());
        assertEquals(PageParseReason.PAGE_EMPTY, decision.reason());
    }

    @Test
    void autoRoutesShortTextPageWithVisualContentToOcr() {
        PdfPageAnalysis page = new PdfPageAnalysis(4, "ZJ-2", 4, 0.25);

        PdfPageDecision decision = policy.decide(page, PdfParseMode.AUTO);

        assertEquals(PageParseStrategy.OCR, decision.strategy());
        assertEquals(PageParseReason.NATIVE_TEXT_INSUFFICIENT, decision.reason());
    }

    @Test
    void autoRoutesShortTextPageToOcrEvenWhenVisualCoverageIsLow() {
        PdfPageAnalysis page = new PdfPageAnalysis(5, "ZJ-2", 4, 0.0);

        PdfPageDecision decision = policy.decide(page, PdfParseMode.AUTO);

        assertEquals(PageParseStrategy.OCR, decision.strategy());
        assertEquals(PageParseReason.NATIVE_TEXT_INSUFFICIENT, decision.reason());
    }

    @Test
    void autoRoutesGarbledNativeTextToOcr() {
        PdfPageAnalysis page = new PdfPageAnalysis(6, "������������设备", 14, 0.0);

        PdfPageDecision decision = policy.decide(page, PdfParseMode.AUTO);

        assertEquals(PageParseStrategy.OCR, decision.strategy());
        assertEquals(PageParseReason.NATIVE_TEXT_LOW_QUALITY, decision.reason());
    }

    @Test
    void autoRoutesMojibakeMadeOfUnicodeLettersToOcr() {
        PdfPageAnalysis page = new PdfPageAnalysis(7, "Ã¦ÂµÂ‹Ã¨Â¯Â•Ã¨Â®Â¾Ã¥Â¤Â‡", 28, 0.0);

        PdfPageDecision decision = policy.decide(page, PdfParseMode.AUTO);

        assertEquals(PageParseStrategy.OCR, decision.strategy());
        assertEquals(PageParseReason.NATIVE_TEXT_LOW_QUALITY, decision.reason());
    }

    @Test
    void forcedModesNeverUseImplicitFallback() {
        PdfPageAnalysis emptyPage = new PdfPageAnalysis(8, "", 0, 0.90);
        PdfPageAnalysis textPage = new PdfPageAnalysis(9, "usable native text content", 26, 0.10);

        PdfPageDecision forcedPdfBox = policy.decide(emptyPage, PdfParseMode.PDFBOX);
        PdfPageDecision forcedOcr = policy.decide(textPage, PdfParseMode.OCR);

        assertEquals(PageParseStrategy.PDFBOX, forcedPdfBox.strategy());
        assertEquals(PageParseReason.FORCED_PDFBOX, forcedPdfBox.reason());
        assertEquals(PageParseStrategy.OCR, forcedOcr.strategy());
        assertEquals(PageParseReason.FORCED_OCR, forcedOcr.reason());
    }
}
