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

/** Typed parsing evidence retained for one PDF page. */
public record PageParseResult(
        int pageNumber,
        PageParseStrategy strategy,
        PageParseReason reason,
        PageParseStatus status,
        int nativeCharacterCount,
        int ocrCharacterCount,
        long durationMillis,
        String text) {

    public PageParseResult {
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must start at 1");
        }
        strategy = Objects.requireNonNull(strategy, "strategy");
        reason = Objects.requireNonNull(reason, "reason");
        status = Objects.requireNonNull(status, "status");
        if (nativeCharacterCount < 0 || ocrCharacterCount < 0 || durationMillis < 0) {
            throw new IllegalArgumentException("character counts and duration must not be negative");
        }
        text = Objects.requireNonNullElse(text, "");
        if (status == PageParseStatus.EMPTY && !text.isBlank()) {
            throw new IllegalArgumentException("EMPTY page must not contain text");
        }
        if (status == PageParseStatus.COMPLETED && text.isBlank()) {
            throw new IllegalArgumentException("COMPLETED page must contain text");
        }
    }
}
