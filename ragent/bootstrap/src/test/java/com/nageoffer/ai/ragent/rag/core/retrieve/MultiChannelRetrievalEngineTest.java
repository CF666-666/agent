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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.dto.RetrievalOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class MultiChannelRetrievalEngineTest {

    @Test
    void shouldReturnCompletedChannelsWhenImageChannelExceedsItsBudget() {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            SearchChannel fastTextChannel = channel("text", SearchChannelType.VECTOR_GLOBAL, 0L,
                    List.of(RetrievedChunk.builder().id("text-1").text("text evidence").score(0.9F).build()));
            SearchChannel slowImageChannel = new SearchChannel() {
                @Override
                public String getName() {
                    return "image";
                }

                @Override
                public int getPriority() {
                    return 20;
                }

                @Override
                public boolean isEnabled(SearchContext context) {
                    return true;
                }

                @Override
                public SearchChannelResult search(SearchContext context) {
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return result(getName(), getType(), List.of());
                }

                @Override
                public long getExecutionTimeoutMillis() {
                    return 50L;
                }

                @Override
                public SearchChannelType getType() {
                    return SearchChannelType.IMAGE_SEMANTIC;
                }
            };
            MultiChannelRetrievalEngine engine = new MultiChannelRetrievalEngine(
                    List.of(fastTextChannel, slowImageChannel), List.of(), executor);

            long startedAt = System.nanoTime();
            List<RetrievedChunk> chunks = engine.retrieveKnowledgeChannels(
                    List.of(new com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent("机械臂图纸", List.of())),
                    5,
                    RetrievalOptions.defaults());
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

            assertThat(chunks).extracting(RetrievedChunk::getId).containsExactly("text-1");
            assertThat(elapsedMillis).isLessThan(500L);
        } finally {
            executor.shutdownNow();
        }
    }

    private static SearchChannel channel(String name,
                                         SearchChannelType type,
                                         long timeoutMillis,
                                         List<RetrievedChunk> chunks) {
        return new SearchChannel() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getPriority() {
                return 10;
            }

            @Override
            public boolean isEnabled(SearchContext context) {
                return true;
            }

            @Override
            public SearchChannelResult search(SearchContext context) {
                return result(name, type, chunks);
            }

            @Override
            public long getExecutionTimeoutMillis() {
                return timeoutMillis;
            }

            @Override
            public SearchChannelType getType() {
                return type;
            }
        };
    }

    private static SearchChannelResult result(String name,
                                              SearchChannelType type,
                                              List<RetrievedChunk> chunks) {
        return SearchChannelResult.builder()
                .channelName(name)
                .channelType(type)
                .chunks(chunks)
                .build();
    }
}
