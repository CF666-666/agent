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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for the versioned PDF page parser policy. */
@Configuration
public class PdfPageParserConfiguration {

    @Bean
    PdfPageParser pdfPageParser(
            OcrPageReader ocrPageReader,
            @Value("${rag.multimodal.pdf.policy-version:pdf-routing-v1}") String policyVersion,
            @Value("${rag.multimodal.pdf.minimum-effective-characters:20}") int minimumCharacters,
            @Value("${rag.multimodal.pdf.minimum-valid-character-ratio:0.50}") double minimumValidRatio,
            @Value("${rag.multimodal.pdf.maximum-mojibake-ratio:0.15}") double maximumMojibakeRatio,
            @Value("${rag.multimodal.pdf.minimum-visual-coverage-ratio:0.02}") double minimumVisualCoverage,
            @Value("${rag.multimodal.pdf.render-dpi:200}") int renderDpi) {
        var thresholds = new PdfTextQualityThresholds(
                policyVersion,
                minimumCharacters,
                minimumValidRatio,
                maximumMojibakeRatio,
                minimumVisualCoverage);
        return new PdfPageParser(
                new PdfBoxPageAnalyzer(),
                new PdfPageRoutingPolicy(thresholds),
                ocrPageReader,
                renderDpi);
    }
}
