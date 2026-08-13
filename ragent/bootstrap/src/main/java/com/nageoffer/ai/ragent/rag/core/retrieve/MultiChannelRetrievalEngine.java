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

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.dto.RetrievalOptions;
import com.nageoffer.ai.ragent.rag.dto.RetrievalChannelStatus;
import com.nageoffer.ai.ragent.rag.dto.RetrievalExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 多通道检索引擎
 * <p>
 * 负责协调多个检索通道和后置处理器：
 * 1. 并行执行所有启用的检索通道
 * 2. 依次执行后置处理器链
 * 3. 返回最终的检索结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiChannelRetrievalEngine {

    private final List<SearchChannel> searchChannels;
    private final List<SearchResultPostProcessor> postProcessors;
    @Qualifier("ragRetrievalThreadPoolExecutor")
    private final ExecutorService ragRetrievalExecutor;

    /**
     * 执行多通道检索（仅 KB 场景）
     *
     * @param subIntents 子问题意图列表
     * @param topK       期望返回的结果数量
     * @return 检索到的 Chunk 列表
     */
    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents, int topK) {
        return retrieveKnowledgeChannels(subIntents, topK, RetrievalOptions.defaults());
    }

    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public List<RetrievedChunk> retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents,
                                                          int topK,
                                                          RetrievalOptions retrievalOptions) {
        return retrieveKnowledgeChannels(subIntents, topK, retrievalOptions,
                RetrievalExecutionContext.unbounded()).chunks();
    }

    @RagTraceNode(name = "multi-channel-retrieval", type = "RETRIEVE_CHANNEL")
    public RetrievalExecutionResult retrieveKnowledgeChannels(List<SubQuestionIntent> subIntents,
                                                               int topK,
                                                               RetrievalOptions retrievalOptions,
                                                               RetrievalExecutionContext executionContext) {
        // 构建检索上下文
        SearchContext context = buildSearchContext(subIntents, topK,
                retrievalOptions == null ? RetrievalOptions.defaults() : retrievalOptions,
                executionContext == null ? RetrievalExecutionContext.unbounded() : executionContext);

        // 【阶段1：多通道并行检索】
        List<SearchChannelResult> channelResults = executeSearchChannels(context);
        if (CollUtil.isEmpty(channelResults)) {
            return new RetrievalExecutionResult(List.of(), List.of());
        }

        // 【阶段2：后置处理器链】
        return new RetrievalExecutionResult(
                executePostProcessors(channelResults, context),
                channelResults.stream().map(result -> toStatus(context, result)).toList());
    }

    /**
     * 执行所有启用的检索通道
     */
    private List<SearchChannelResult> executeSearchChannels(SearchContext context) {
        // 过滤启用的通道
        List<SearchChannel> enabledChannels = searchChannels.stream()
                .filter(channel -> channel.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchChannel::getPriority))
                .toList();

        if (enabledChannels.isEmpty()) {
            return List.of();
        }

        log.info("启用的检索通道：{}",
                enabledChannels.stream().map(SearchChannel::getName).toList());

        List<ChannelExecution> futures = enabledChannels.stream()
                .map(channel -> executeChannel(channel, context))
                .toList();

        // 等待所有通道完成并统计
        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<SearchChannelResult> results = futures.stream()
                .map(ChannelExecution::await)
                .filter(Objects::nonNull)
                .toList();

        // 打印详细统计信息
        for (SearchChannelResult result : results) {
            int chunkCount = result.getChunks().size();
            totalChunks += chunkCount;

            if (chunkCount > 0) {
                successCount++;
                log.info("通道 {} 完成 ✓ - 检索到 {} 个 Chunk，耗时：{}ms",
                        result.getChannelName(),
                        chunkCount,
                        result.getLatencyMs()
                );
            } else if (Boolean.TRUE.equals(result.getMetadata().get("timedOut"))) {
                failureCount++;
                log.warn("通道 {} 超过 {}ms 执行预算，已降级为无结果",
                        result.getChannelName(), result.getMetadata().get("timeoutMillis"));
            } else {
                failureCount++;
                log.warn("通道 {} 完成但无结果 - 耗时：{}ms",
                        result.getChannelName(),
                        result.getLatencyMs()
                );
            }
        }

        log.info("多通道检索统计 - 总通道数: {}, 有结果: {}, 无结果: {}, Chunk 总数: {}",
                enabledChannels.size(), successCount, failureCount, totalChunks);

        return results;
    }

    private ChannelExecution executeChannel(SearchChannel channel, SearchContext context) {
        RetrievalExecutionContext channelExecution = context.getExecutionContext().fork();
        long submittedAtNanos = System.nanoTime();
        long budgetMillis = initialBudgetMillis(channel, channelExecution);
        SearchContext channelContext = SearchContext.builder()
                .originalQuestion(context.getOriginalQuestion())
                .rewrittenQuestion(context.getRewrittenQuestion())
                .subQuestions(context.getSubQuestions())
                .intents(context.getIntents())
                .topK(context.getTopK())
                .retrievalOptions(context.getRetrievalOptions())
                .metadata(context.getMetadata())
                .executionContext(channelExecution)
                .build();
        FutureTask<SearchChannelResult> task = new FutureTask<>(
                () -> {
                    try {
                        log.info("执行检索通道：{}", channel.getName());
                        return channel.search(channelContext);
                    } catch (java.util.concurrent.CancellationException exception) {
                        throw exception;
                    } catch (Exception e) {
                        log.error("检索通道 {} 执行失败", channel.getName(), e);
                        return emptyResult(channel, 0L);
                    }
                }
        );
        channelExecution.register(task);
        scheduleBudgetExpiry(task, channelExecution, budgetMillis);
        ragRetrievalExecutor.execute(task);
        return new ChannelExecution(channel, task, channelExecution, submittedAtNanos, budgetMillis);
    }

    private void scheduleBudgetExpiry(FutureTask<SearchChannelResult> task,
                                      RetrievalExecutionContext executionContext,
                                      long budgetMillis) {
        if (budgetMillis == Long.MAX_VALUE) {
            return;
        }
        CompletableFuture.delayedExecutor(budgetMillis, TimeUnit.MILLISECONDS).execute(() -> {
            if (!task.isDone()) {
                executionContext.timeout();
            }
        });
    }

    private long initialBudgetMillis(SearchChannel channel, RetrievalExecutionContext executionContext) {
        long requestRemaining = executionContext.remainingMillis();
        long channelBudget = channel.getExecutionTimeoutMillis();
        if (channelBudget <= 0L) {
            return requestRemaining;
        }
        return Math.min(channelBudget, requestRemaining);
    }

    /**
     * 执行后置处理器链
     */
    private List<RetrievedChunk> executePostProcessors(List<SearchChannelResult> results,
                                                       SearchContext context) {
        // 过滤启用的处理器并排序
        List<SearchResultPostProcessor> enabledProcessors = postProcessors.stream()
                .filter(processor -> processor.isEnabled(context))
                .sorted(Comparator.comparingInt(SearchResultPostProcessor::getOrder))
                .toList();

        if (enabledProcessors.isEmpty()) {
            log.warn("没有启用的后置处理器，直接返回原始结果");
            return results.stream()
                    .flatMap(r -> r.getChunks().stream())
                    .collect(Collectors.toList());
        }

        // 初始 Chunk 列表（所有通道的结果合并）
        List<RetrievedChunk> chunks = results.stream()
                .flatMap(r -> r.getChunks().stream())
                .collect(Collectors.toList());

        int initialSize = chunks.size();

        // 依次执行处理器
        for (SearchResultPostProcessor processor : enabledProcessors) {
            try {
                int beforeSize = chunks.size();
                chunks = processor.process(chunks, results, context);
                int afterSize = chunks.size();

                log.info("后置处理器 {} 完成 - 输入: {} 个 Chunk, 输出: {} 个 Chunk, 变化: {}",
                        processor.getName(),
                        beforeSize,
                        afterSize,
                        (afterSize - beforeSize > 0 ? "+" : "") + (afterSize - beforeSize)
                );
            } catch (Exception e) {
                log.error("后置处理器 {} 执行失败，跳过该处理器", processor.getName(), e);
                // 继续执行下一个处理器，不中断整个链
            }
        }

        log.info("后置处理器链执行完成 - 初始: {} 个 Chunk, 最终: {} 个 Chunk",
                initialSize, chunks.size());

        return chunks;
    }

    private SearchChannelResult emptyResult(SearchChannel channel, long elapsedMillis) {
        return SearchChannelResult.builder()
                .channelType(channel.getType())
                .channelName(channel.getName())
                .chunks(List.of())
                .latencyMs(elapsedMillis)
                .metadata(java.util.Map.of("status", "FAILED"))
                .build();
    }

    private SearchChannelResult cancelledResult(SearchChannel channel, long budgetMillis, long elapsedMillis) {
        return SearchChannelResult.builder()
                .channelType(channel.getType())
                .channelName(channel.getName())
                .chunks(List.of())
                .latencyMs(elapsedMillis)
                .metadata(java.util.Map.of("status", "CANCELLED", "cancelled", true,
                        "budgetMillis", budgetMillis))
                .build();
    }

    private RetrievalChannelStatus toStatus(SearchContext context, SearchChannelResult result) {
        java.util.Map<String, Object> metadata = result.getMetadata() == null ? java.util.Map.of() : result.getMetadata();
        String status = String.valueOf(metadata.getOrDefault("status", "COMPLETED"));
        boolean timedOut = Boolean.TRUE.equals(metadata.get("timedOut"));
        boolean cancelled = Boolean.TRUE.equals(metadata.get("cancelled"));
        long budgetMillis = ((Number) metadata.getOrDefault("budgetMillis", 0L)).longValue();
        return new RetrievalChannelStatus(
                context.getMainQuestion(), result.getChannelName(), status, timedOut, cancelled,
                budgetMillis, result.getLatencyMs());
    }

    private SearchChannelResult timeoutResult(SearchChannel channel, long budgetMillis, long elapsedMillis) {
        return SearchChannelResult.builder()
                .channelType(channel.getType())
                .channelName(channel.getName())
                .chunks(List.of())
                .latencyMs(elapsedMillis)
                .metadata(java.util.Map.of("timedOut", true, "cancelled", true,
                        "status", "TIMED_OUT", "timeoutMillis", budgetMillis,
                        "budgetMillis", budgetMillis))
                .build();
    }

    private final class ChannelExecution {
        private final SearchChannel channel;
        private final FutureTask<SearchChannelResult> task;
        private final RetrievalExecutionContext executionContext;
        private final long submittedAtNanos;
        private final long budgetMillis;

        private ChannelExecution(SearchChannel channel,
                                 FutureTask<SearchChannelResult> task,
                                 RetrievalExecutionContext executionContext,
                                 long submittedAtNanos,
                                 long budgetMillis) {
            this.channel = channel;
            this.task = task;
            this.executionContext = executionContext;
            this.submittedAtNanos = submittedAtNanos;
            this.budgetMillis = budgetMillis;
        }

        private SearchChannelResult await() {
            long timeoutMillis = effectiveTimeoutMillis();
            if (timeoutMillis <= 0L) {
                executionContext.timeout();
                return timeoutResult(channel, budgetMillis, timeoutElapsedMillis());
            }
            try {
                return task.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                executionContext.timeout();
                return timeoutResult(channel, budgetMillis, timeoutElapsedMillis());
            } catch (java.util.concurrent.CancellationException ignored) {
                if (executionContext.state() == RetrievalExecutionContext.State.TIMED_OUT) {
                    return timeoutResult(channel, budgetMillis, timeoutElapsedMillis());
                }
                return cancelledResult(channel, budgetMillis, elapsedMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                executionContext.cancel();
                return emptyResult(channel, elapsedMillis());
            } catch (ExecutionException exception) {
                return emptyResult(channel, elapsedMillis());
            } finally {
                executionContext.unregister(task);
                executionContext.close();
            }
        }

        private long effectiveTimeoutMillis() {
            long remaining = executionContext.remainingMillis();
            if (budgetMillis == Long.MAX_VALUE) {
                return remaining;
            }
            return Math.min(Math.max(0L, budgetMillis - elapsedMillis()), remaining);
        }

        private long elapsedMillis() {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submittedAtNanos);
        }

        private long timeoutElapsedMillis() {
            if (budgetMillis == Long.MAX_VALUE) {
                return elapsedMillis();
            }
            return Math.min(elapsedMillis(), budgetMillis);
        }
    }

    /**
     * 构建检索上下文
     */
    private SearchContext buildSearchContext(List<SubQuestionIntent> subIntents,
                                             int topK,
                                             RetrievalOptions retrievalOptions,
                                             RetrievalExecutionContext executionContext) {
        String question = CollUtil.isEmpty(subIntents) ? "" : subIntents.get(0).subQuestion();

        return SearchContext.builder()
                .originalQuestion(question)
                .rewrittenQuestion(question)
                .intents(subIntents)
                .topK(topK)
                .retrievalOptions(retrievalOptions)
                .executionContext(executionContext)
                .build();
    }
}
