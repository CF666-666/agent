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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonElement;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.config.StaticResourceProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.Reference;
import com.nageoffer.ai.ragent.rag.dto.ReferenceType;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

/**
 * 流式对话流水线
 * <p>
 * 承载从 RAGChatServiceImpl 提取的业务编排逻辑：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 系统响应 / 检索 -> Prompt 组装 -> 流式输出
 * <p>
 * 流水线模式：通过私有方法 + boolean 返回值（handleXxx 返回 true 表示已处理并短路）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamChatPipeline {

    private final ConversationMemoryService memoryService;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final StreamTaskManager taskManager;
    private final StaticResourceProperties staticResourceProperties;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 执行流式对话管道
     */
    public void execute(StreamChatContext ctx) {
        loadMemory(ctx);
        rewriteQuery(ctx);
        resolveIntents(ctx);

        if (handleGuidance(ctx)) {
            return;
        }
        if (handleSystemOnly(ctx)) {
            return;
        }

        RetrievalContext retrievalCtx = retrieve(ctx);
        if (handleEmptyRetrieval(ctx, retrievalCtx)) {
            return;
        }

        streamRagResponse(ctx, retrievalCtx);
    }

    // ==================== 流水线阶段 ====================

    private void loadMemory(StreamChatContext ctx) {
        List<ChatMessage> history = memoryService.loadAndAppend(
                ctx.getConversationId(),
                ctx.getUserId(),
                ChatMessage.user(ctx.getQuestion())
        );
        ctx.setHistory(history);
    }

    private void rewriteQuery(StreamChatContext ctx) {
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(ctx.getQuestion(), ctx.getHistory());
        ctx.setRewriteResult(rewriteResult);
    }

    private void resolveIntents(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = intentResolver.resolve(ctx.getRewriteResult());
        ctx.setSubIntents(subIntents);
    }

    private boolean handleGuidance(StreamChatContext ctx) {
        GuidanceDecision decision = guidanceService.detectAmbiguity(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getSubIntents()
        );
        if (!decision.isPrompt()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent(decision.getPrompt());
        callback.onComplete();
        return true;
    }

    private boolean handleSystemOnly(StreamChatContext ctx) {
        List<SubQuestionIntent> subIntents = ctx.getSubIntents();
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        if (!allSystemOnly) {
            return false;
        }
        String customPrompt = subIntents.stream()
                .flatMap(si -> si.nodeScores().stream())
                .map(ns -> ns.getNode().getPromptTemplate())
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
        StreamCancellationHandle handle = streamSystemResponse(
                ctx.getRewriteResult().rewrittenQuestion(),
                ctx.getHistory(),
                customPrompt,
                ctx.getCallback()
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
        return true;
    }

    private RetrievalContext retrieve(StreamChatContext ctx) {
        return retrievalEngine.retrieve(ctx.getSubIntents(), DEFAULT_TOP_K);
    }

    private boolean handleEmptyRetrieval(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        if (!retrievalCtx.isEmpty()) {
            return false;
        }
        StreamCallback callback = ctx.getCallback();
        callback.onContent("未检索到与问题相关的文档内容。");
        callback.onComplete();
        return true;
    }

    private void streamRagResponse(StreamChatContext ctx, RetrievalContext retrievalCtx) {
        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(ctx.getSubIntents());

        // Phase 4/6: 检索来源在 LLM 流开始前发送
        // 保证取消/中断场景下引用也已到达（挂在 onComplete 会在取消时丢失）
        List<Reference> references = buildReferences(retrievalCtx);
        StreamCallback callback = ctx.getCallback();
        if (!references.isEmpty()) {
            callback.onReferences(toJson(references));
        }

        StreamCancellationHandle handle = streamLLMResponse(
                ctx.getRewriteResult(),
                retrievalCtx,
                mergedGroup,
                ctx.getHistory(),
                ctx.isDeepThinking(),
                callback
        );
        taskManager.bindHandle(ctx.getTaskId(), handle);
    }

    // ==================== LLM 响应 ====================

    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history);
        }
        messages.add(ChatMessage.user(question));

        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())
                .mcpContext(ctx.getMcpContext())
                .kbContext(ctx.getKbContext())
                .mcpIntents(intentGroup.mcpIntents())
                .kbIntents(intentGroup.kbIntents())
                .intentChunks(ctx.getIntentChunks())
                .build();

        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,
                rewriteResult.rewrittenQuestion(),
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)
                .build();

        return llmService.streamChat(chatRequest, callback);
    }

    // ==================== Phase 4: References 构建 ====================

    /**
     * 从检索上下文中构建结构化引用列表
     * 6→3 映射：4 种文本通道 → TEXT，IMAGE_SEMANTIC → IMAGE，HYPERGRAPH → HYPERGRAPH
     */
    private List<Reference> buildReferences(RetrievalContext ctx) {
        List<Reference> refs = new ArrayList<>();
        if (ctx.getIntentChunks() == null) {
            return refs;
        }
        for (List<RetrievedChunk> chunks : ctx.getIntentChunks().values()) {
            for (RetrievedChunk chunk : chunks) {
                Map<String, Object> meta = chunk.getMetadata();
                if (meta == null) continue;

                Object sourceRaw = meta.get("source");
                if (sourceRaw == null) continue;
                String source = String.valueOf(sourceRaw);

                ReferenceType type = mapToReferenceType(source);
                Reference ref = buildReference(type, chunk, meta);
                // 静态资源未启用时 IMAGE 引用无 url，过滤掉避免"有计数无卡片"
                if (type == ReferenceType.IMAGE && StrUtil.isBlank(ref.url())) {
                    continue;
                }
                refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * 6→3 映射：SearchChannelType.name() → ReferenceType
     */
    private ReferenceType mapToReferenceType(String source) {
        return switch (source) {
            case "IMAGE_SEMANTIC" -> ReferenceType.IMAGE;
            case "HYPERGRAPH" -> ReferenceType.HYPERGRAPH;
            default -> ReferenceType.TEXT;
        };
    }

    /**
     * 从 chunk + metadata 构造单条 Reference
     */
    private Reference buildReference(ReferenceType type, RetrievedChunk chunk, Map<String, Object> meta) {
        String label = resolveLabel(type, meta);
        String url = resolveUrl(type, meta);
        String detail = resolveDetail(type, meta);
        String snippet = resolveSnippet(chunk);
        Map<String, Object> extra = resolveExtra(chunk, meta);
        return new Reference(type, label, url, detail, snippet, extra);
    }

    private String resolveLabel(ReferenceType type, Map<String, Object> meta) {
        return switch (type) {
            case IMAGE -> {
                Object path = meta.get("imagePath");
                if (path != null) {
                    String pathStr = path instanceof JsonElement je ? je.getAsString() : path.toString();
                    yield "设备图纸: " + pathStr;
                }
                yield "设备图纸";
            }
            case HYPERGRAPH -> "推理路径";
            case TEXT -> "文本引用";
        };
    }

    private String resolveUrl(ReferenceType type, Map<String, Object> meta) {
        if (type != ReferenceType.IMAGE) return null;
        // 静态资源映射未启用时，不产出失效的图片引用
        if (!staticResourceProperties.isEnabled()) {
            return null;
        }
        Object path = meta.get("imagePath");
        if (path == null) return null;
        String imagePath = path instanceof JsonElement je ? je.getAsString() : path.toString();
        // 归一化并校验：拒绝 ../ 路径穿越（防止越出静态资源根目录）
        if (!isSafeAssetPath(imagePath)) {
            log.warn("引用图片路径不安全，已跳过: {}", imagePath);
            return null;
        }
        // 图片走静态资源映射：/files/** → data/images/，前缀由配置派生，与 WebConfig 保持一致
        String urlPrefix = staticResourceProperties.getUrlPattern().replace("/**", "");
        return urlPrefix + "/" + encodePathSegments(imagePath);
    }

    /**
     * 对资源相对路径做 URL 编码（分段处理，保留 {@code /} 分隔符）
     * <p>
     * 防止中文 / 空格 / 特殊字符（#、?）导致图片加载失败。
     * </p>
     */
    private static String encodePathSegments(String path) {
        String[] segments = path.split("/");
        StringBuilder encoded = new StringBuilder();
        for (String segment : segments) {
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return encoded.toString();
    }

    /**
     * 校验资源相对路径安全：仅允许普通文件名 / 子目录，拒绝 {@code ..}、绝对路径、协议前缀
     */
    private static boolean isSafeAssetPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        // 拒绝绝对路径（/xxx、C:\xxx、file:...）与协议前缀
        if (path.startsWith("/") || path.contains(":/") || path.contains(":\\")) {
            return false;
        }
        // 归一化后不得包含上层目录引用
        String normalized = Paths.get(path).normalize().toString().replace('\\', '/');
        return !normalized.startsWith("..") && !normalized.contains("../");
    }

    private String resolveDetail(ReferenceType type, Map<String, Object> meta) {
        if (type == ReferenceType.HYPERGRAPH) {
            Object path = meta.get("hyperEdgePath");
            if (path == null) return null;
            return path instanceof JsonElement je ? je.getAsString() : path.toString();
        }
        return null;
    }

    private String resolveSnippet(RetrievedChunk chunk) {
        String text = chunk.getText();
        if (text == null) return null;
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    private Map<String, Object> resolveExtra(RetrievedChunk chunk, Map<String, Object> meta) {
        Map<String, Object> extra = new HashMap<>();
        if (chunk.getScore() != null) extra.put("score", (double) chunk.getScore());
        Object matchCount = meta.get("matchCount");
        if (matchCount instanceof Number) extra.put("matchCount", matchCount);
        return extra;
    }

    /**
     * Jackson 序列化引用列表（绕过 double-wrap）
     */
    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("序列化 references 失败", e);
            return "[]";
        }
    }
}
