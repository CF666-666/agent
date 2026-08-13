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

package com.nageoffer.ai.ragent.ingestion.domain.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.DocumentParseResult;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseReason;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseResult;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseStatus;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PageParseStrategy;
import com.nageoffer.ai.ragent.multimodal.parser.pdf.PdfParseMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IngestionContextPdfCheckpointTest {

    @Test
    void typedPdfEvidenceSurvivesCheckpointJsonRoundTrip() throws Exception {
        IngestionContext context = IngestionContext.builder()
                .taskId("resume-task")
                .pdfParseResult(new DocumentParseResult(
                        "manual.pdf",
                        PdfParseMode.AUTO,
                        "pdf-routing-v1",
                        12,
                        List.of(new PageParseResult(
                                1,
                                PageParseStrategy.OCR,
                                PageParseReason.VISUAL_CONTENT_WITHOUT_TEXT,
                                PageParseStatus.COMPLETED,
                                0,
                                8,
                                12,
                                "ocr text"))))
                .build();
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        IngestionCheckpoint checkpoint = IngestionCheckpoint.from(context);
        IngestionCheckpoint restoredCheckpoint = mapper.readValue(
                mapper.writeValueAsBytes(checkpoint), IngestionCheckpoint.class);
        IngestionContext restored = IngestionContext.builder().build();
        restoredCheckpoint.restoreTo(restored);

        assertEquals(PdfParseMode.AUTO, restored.getPdfParseResult().mode());
        assertEquals(PageParseStrategy.OCR, restored.getPdfParseResult().pages().get(0).strategy());
        assertEquals("ocr text", restored.getPdfParseResult().pages().get(0).text());
    }
}
