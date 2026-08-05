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

package com.nageoffer.ai.ragent.rag.dto;

/** 请求级检索开关，默认保持完整检索链路开启。 */
public record RetrievalOptions(
        boolean enableRewrite,
        boolean enableImage,
        boolean enableHyperGraph,
        boolean enableFusion
) {

    public static RetrievalOptions defaults() {
        return new RetrievalOptions(true, true, true, true);
    }

    /** 缺失 HTTP 参数时沿用历史行为：默认开启。 */
    public static RetrievalOptions from(Boolean enableRewrite,
                                        Boolean enableImage,
                                        Boolean enableHyperGraph,
                                        Boolean enableFusion) {
        return new RetrievalOptions(
                enabledOrDefault(enableRewrite),
                enabledOrDefault(enableImage),
                enabledOrDefault(enableHyperGraph),
                enabledOrDefault(enableFusion)
        );
    }

    private static boolean enabledOrDefault(Boolean value) {
        return value == null || value;
    }
}
