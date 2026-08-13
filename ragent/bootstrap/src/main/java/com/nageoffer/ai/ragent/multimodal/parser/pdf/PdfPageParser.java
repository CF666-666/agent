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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Coordinates page analysis, routing, rendering and OCR for one PDF. */
public final class PdfPageParser {

    private final PdfBoxPageAnalyzer analyzer;
    private final PdfPageRoutingPolicy routingPolicy;
    private final OcrPageReader ocrPageReader;
    private final int renderDpi;

    public PdfPageParser(
            PdfBoxPageAnalyzer analyzer,
            PdfPageRoutingPolicy routingPolicy,
            OcrPageReader ocrPageReader,
            int renderDpi) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.routingPolicy = Objects.requireNonNull(routingPolicy, "routingPolicy");
        this.ocrPageReader = Objects.requireNonNull(ocrPageReader, "ocrPageReader");
        if (renderDpi < 72) {
            throw new IllegalArgumentException("renderDpi must be at least 72");
        }
        this.renderDpi = renderDpi;
    }

    public DocumentParseResult parse(File file, PdfParseMode mode) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(mode, "mode");
        long documentStart = System.nanoTime();
        List<PdfPageAnalysis> analyses = analyzer.analyze(file);
        try (PDDocument document = Loader.loadPDF(file)) {
            if (document.getNumberOfPages() != analyses.size()) {
                throw new IllegalStateException("PDF changed during page parsing: " + file.getName());
            }
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageParseResult> pages = new ArrayList<>(analyses.size());
            for (PdfPageAnalysis analysis : analyses) {
                pages.add(parsePage(analysis, mode, renderer));
            }
            return new DocumentParseResult(
                    file.getAbsolutePath(),
                    mode,
                    routingPolicy.policyVersion(),
                    elapsedMillis(documentStart),
                    pages);
        } catch (IOException exception) {
            throw new IllegalStateException("PDF rendering failed: " + file.getName(), exception);
        }
    }

    private PageParseResult parsePage(
            PdfPageAnalysis analysis, PdfParseMode mode, PDFRenderer renderer) {
        long pageStart = System.nanoTime();
        PdfPageDecision decision = routingPolicy.decide(analysis, mode);
        if (decision.strategy() == PageParseStrategy.EMPTY) {
            return result(
                    analysis, decision, PageParseStatus.EMPTY, 0, elapsedMillis(pageStart), "");
        }
        if (decision.strategy() == PageParseStrategy.PDFBOX) {
            String text = analysis.nativeText().trim();
            PageParseStatus status = text.isEmpty()
                    ? PageParseStatus.EMPTY
                    : PageParseStatus.COMPLETED;
            return result(analysis, decision, status, 0, elapsedMillis(pageStart), text);
        }

        java.awt.image.BufferedImage image = null;
        try {
            image = renderer.renderImageWithDPI(
                    analysis.pageNumber() - 1, renderDpi, ImageType.RGB);
            String text = Objects.requireNonNullElse(
                            ocrPageReader.read(image, analysis.pageNumber()), "")
                    .trim();
            if (text.isEmpty()) {
                throw new IllegalStateException(
                        "OCR returned empty text at page " + analysis.pageNumber());
            }
            return result(
                    analysis,
                    decision,
                    PageParseStatus.COMPLETED,
                    text.codePointCount(0, text.length()),
                    elapsedMillis(pageStart),
                    text);
        } catch (RuntimeException | IOException exception) {
            if (exception instanceof IllegalStateException
                    && exception.getMessage() != null
                    && exception.getMessage().contains("page " + analysis.pageNumber())) {
                throw (IllegalStateException) exception;
            }
            throw new IllegalStateException(
                    "OCR failed at page " + analysis.pageNumber(), exception);
        } finally {
            if (image != null) {
                image.flush();
            }
        }
    }

    private PageParseResult result(
            PdfPageAnalysis analysis,
            PdfPageDecision decision,
            PageParseStatus status,
            int ocrCharacters,
            long durationMillis,
            String text) {
        return new PageParseResult(
                analysis.pageNumber(),
                decision.strategy(),
                decision.reason(),
                status,
                analysis.nativeCharacterCount(),
                ocrCharacters,
                durationMillis,
                text);
    }

    private long elapsedMillis(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
    }
}
