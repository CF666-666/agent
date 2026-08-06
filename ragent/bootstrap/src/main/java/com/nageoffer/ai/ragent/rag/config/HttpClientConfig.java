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

package com.nageoffer.ai.ragent.rag.config;

import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * HTTP 客户端配置类
 */
@Configuration
public class HttpClientConfig {

    /**
     * 流式 HTTP 客户端（Primary）
     */
    @Bean
    @Primary
    public OkHttpClient streamingHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ZERO)
                .callTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 同步 HTTP 客户端
     */
    @Bean
    public OkHttpClient syncHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(45))
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * Rerank is an optional post-processor: it must degrade to the pre-ranked
     * candidates instead of consuming the whole retrieval response budget.
     */
    @Bean
    @Qualifier("rerankHttpClient")
    public OkHttpClient rerankHttpClient(@Value("${rag.rerank.timeout-millis:3000}") long timeoutMillis) {
        Duration timeout = Duration.ofMillis(Math.max(1, timeoutMillis));
        return new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .writeTimeout(timeout)
                .readTimeout(timeout)
                .callTimeout(timeout)
                .retryOnConnectionFailure(false)
                .build();
    }

    /**
     * Embedding is a synchronous dependency of both indexing and retrieval.
     * Its provider call must not inherit the general-purpose client's 45-second
     * budget, otherwise a single slow embedding can outlive the SSE request.
     */
    @Bean
    @Qualifier("embeddingHttpClient")
    public OkHttpClient embeddingHttpClient(@Value("${rag.embedding.timeout-millis:5000}") long timeoutMillis) {
        Duration timeout = Duration.ofMillis(Math.max(1, timeoutMillis));
        return new OkHttpClient.Builder()
                .connectTimeout(timeout)
                .writeTimeout(timeout)
                .readTimeout(timeout)
                .callTimeout(timeout)
                .retryOnConnectionFailure(false)
                .build();
    }
}
