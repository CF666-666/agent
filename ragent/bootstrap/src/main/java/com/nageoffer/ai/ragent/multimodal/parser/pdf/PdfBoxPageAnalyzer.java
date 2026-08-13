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
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFTextStripper;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Uses PDFBox to collect native text and placed-image coverage for every page. */
public final class PdfBoxPageAnalyzer {

    public List<PdfPageAnalysis> analyze(File file) {
        Objects.requireNonNull(file, "file");
        try (PDDocument document = Loader.loadPDF(file)) {
            List<PdfPageAnalysis> pages = new ArrayList<>(document.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                int pageNumber = pageIndex + 1;
                stripper.setStartPage(pageNumber);
                stripper.setEndPage(pageNumber);
                String nativeText = stripper.getText(document);
                PDPage page = document.getPage(pageIndex);
                double coverage = new ImageCoverageEngine(page).coverageRatio();
                pages.add(new PdfPageAnalysis(
                        pageNumber, nativeText, nativeText.codePointCount(0, nativeText.length()), coverage));
            }
            return List.copyOf(pages);
        } catch (IOException exception) {
            throw new IllegalStateException("PDF page analysis failed: " + file.getName(), exception);
        }
    }

    private static final class ImageCoverageEngine extends PDFGraphicsStreamEngine {

        private final Area imageArea = new Area();
        private final Rectangle2D pageBounds;
        private final Path2D currentPath = new Path2D.Double();
        private Point2D currentPoint;

        private ImageCoverageEngine(PDPage page) {
            super(page);
            PDRectangle cropBox = page.getCropBox();
            pageBounds = new Rectangle2D.Double(
                    cropBox.getLowerLeftX(),
                    cropBox.getLowerLeftY(),
                    cropBox.getWidth(),
                    cropBox.getHeight());
        }

        private double coverageRatio() throws IOException {
            processPage(getPage());
            if (pageBounds.getWidth() <= 0 || pageBounds.getHeight() <= 0) {
                return 0.0;
            }
            Area clipped = new Area(imageArea);
            clipped.intersect(new Area(pageBounds));
            double ratio = area(clipped) / (pageBounds.getWidth() * pageBounds.getHeight());
            return Math.max(0.0, Math.min(1.0, ratio));
        }

        private double area(Area area) {
            var iterator = area.getPathIterator(null, 0.25);
            double[] coordinates = new double[6];
            double total = 0.0;
            double ring = 0.0;
            double startX = 0.0;
            double startY = 0.0;
            double previousX = 0.0;
            double previousY = 0.0;
            while (!iterator.isDone()) {
                int segment = iterator.currentSegment(coordinates);
                if (segment == java.awt.geom.PathIterator.SEG_MOVETO) {
                    startX = previousX = coordinates[0];
                    startY = previousY = coordinates[1];
                    ring = 0.0;
                } else if (segment == java.awt.geom.PathIterator.SEG_LINETO) {
                    ring += previousX * coordinates[1] - coordinates[0] * previousY;
                    previousX = coordinates[0];
                    previousY = coordinates[1];
                } else if (segment == java.awt.geom.PathIterator.SEG_CLOSE) {
                    ring += previousX * startY - startX * previousY;
                    total += ring / 2.0;
                }
                iterator.next();
            }
            return Math.abs(total);
        }

        @Override
        public void drawImage(PDImage image) {
            var transform = getGraphicsState().getCurrentTransformationMatrix();
            Path2D unitSquare = new Path2D.Double();
            unitSquare.moveTo(0, 0);
            unitSquare.lineTo(1, 0);
            unitSquare.lineTo(1, 1);
            unitSquare.lineTo(0, 1);
            unitSquare.closePath();
            Area placedImage = new Area(
                    transform.createAffineTransform().createTransformedShape(unitSquare));
            placedImage.intersect(getGraphicsState().getCurrentClippingPath());
            imageArea.add(placedImage);
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            currentPath.moveTo(p0.getX(), p0.getY());
            currentPath.lineTo(p1.getX(), p1.getY());
            currentPath.lineTo(p2.getX(), p2.getY());
            currentPath.lineTo(p3.getX(), p3.getY());
            currentPath.closePath();
            currentPoint = p0;
        }

        @Override
        public void clip(int windingRule) {
            currentPath.setWindingRule(windingRule);
            getGraphicsState().intersectClippingPath(new Area(currentPath));
        }

        @Override
        public void moveTo(float x, float y) {
            currentPath.moveTo(x, y);
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            currentPath.lineTo(x, y);
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            currentPath.curveTo(x1, y1, x2, y2, x3, y3);
            currentPoint = new Point2D.Float(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return currentPoint;
        }

        @Override
        public void closePath() {
            currentPath.closePath();
        }

        @Override
        public void endPath() {
            currentPath.reset();
            currentPoint = null;
        }

        @Override
        public void strokePath() {
            endPath();
        }

        @Override
        public void fillPath(int windingRule) {
            endPath();
        }

        @Override
        public void fillAndStrokePath(int windingRule) {
            endPath();
        }

        @Override
        public void shadingFill(COSName shadingName) {}
    }
}
