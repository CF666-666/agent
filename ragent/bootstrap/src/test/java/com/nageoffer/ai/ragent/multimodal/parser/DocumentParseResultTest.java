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

import com.nageoffer.ai.ragent.multimodal.parser.pdf.DocumentParseResult;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseReason;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseResult;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseStatus;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseStrategy;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfParseMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentParseResultTest {

    @Test
    void keepsOrderedTypedPageEvidence() {
        PageParseResult nativePage = new PageParseResult(
                1,
                PageParseStrategy.PDFBOX,
                PageParseReason.NATIVE_TEXT_USABLE,
                PageParseStatus.COMPLETED,
                120,
                0,
                14,
                "native page");
        PageParseResult ocrPage = new PageParseResult(
                2,
                PageParseStrategy.OCR,
                PageParseReason.VISUAL_CONTENT_WITHOUT_TEXT,
                PageParseStatus.COMPLETED,
                0,
                86,
                340,
                "ocr page");

        DocumentParseResult result = new DocumentParseResult(
                "manual.pdf", PdfParseMode.AUTO, "pdf-routing-v1", 354, List.of(nativePage, ocrPage));

        assertEquals(List.of(1, 2), result.pages().stream().map(PageParseResult::pageNumber).toList());
        assertEquals("native page\n\nocr page", result.mergedText());
        assertEquals(List.of(2), result.ocrPageNumbers());
    }

    @Test
    void rejectsDuplicateOrOutOfOrderPageEvidence() {
        PageParseResult pageTwo = new PageParseResult(
                2,
                PageParseStrategy.EMPTY,
                PageParseReason.PAGE_EMPTY,
                PageParseStatus.EMPTY,
                0,
                0,
                1,
                "");
        PageParseResult pageOne = new PageParseResult(
                1,
                PageParseStrategy.PDFBOX,
                PageParseReason.NATIVE_TEXT_USABLE,
                PageParseStatus.COMPLETED,
                20,
                0,
                2,
                "page one");

        assertThrows(
                IllegalArgumentException.class,
                () -> new DocumentParseResult(
                        "manual.pdf", PdfParseMode.AUTO, "pdf-routing-v1", 3, List.of(pageTwo, pageOne)));
    }
}
