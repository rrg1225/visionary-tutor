package com.visionary.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visionary.agent.ConversationContext;
import com.visionary.agent.ConversationContextService;
import com.visionary.agent.MemoryStatus;
import com.visionary.agent.learning.LearningAgentPlan;
import com.visionary.agent.learning.LearningSupervisorService;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.dto.ChatMessageDto;
import com.visionary.dto.StreamChatRequest;
import com.visionary.exception.GuestChatQuotaExceededException;
import com.visionary.rag.GroundingAuditService;
import com.visionary.rag.RagRetrievalResult;
import com.visionary.rag.RagRetrievalService;
import com.visionary.rag.VectorDbService;
import com.visionary.security.CustomUserDetails;
import com.visionary.service.GuestSessionService;
import com.visionary.service.LearningMemoryService;
import com.visionary.service.ChatHistoryService;
import com.visionary.service.ChatAnswerCacheService;
import com.visionary.service.AiTeacherPreferencePolicy;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production SSE endpoints: RAG retrieval + non-blocking DeepSeek streaming.
 */
@Slf4j
@RestController
@RequestMapping("/api/stream")
public class AiStreamController {

    private static final long SSE_TIMEOUT_MS = 300_000L;
    private static final int MAX_RAG_CONTEXT_CHARS = 6_000;
    private static final String EVENT_MEMORY_STATUS = "memory_status";
    private static final String EVENT_CONTENT = "content";
    private static final String EVENT_COMPLETE = "complete";
    private static final String EVENT_ERROR = "error";
    private static final String EVENT_RAG_CONTEXT = "rag_context";
    private static final String EVENT_GROUNDING_AUDIT = "grounding_audit";
    private static final String EVENT_STATUS = "status";
    private static final String EVENT_AGENT_TRACE = "agent_trace";

    private final RagRetrievalService ragRetrievalService;
    private final DeepSeekApiClient deepSeekApiClient;
    private final ConversationContextService conversationContextService;
    private final ObjectMapper objectMapper;
    private final VectorDbService vectorDbService;
    private final GuestSessionService guestSessionService;
    private final LearningMemoryService learningMemoryService;
    private final ChatHistoryService chatHistoryService;
    private final ChatAnswerCacheService chatAnswerCacheService;
    private final GroundingAuditService groundingAuditService;
    private final LearningSupervisorService learningSupervisorService;
    private final AiTeacherPreferencePolicy aiTeacherPreferencePolicy;
    private final Executor sseStreamExecutor;

    @Autowired
    public AiStreamController(
            RagRetrievalService ragRetrievalService,
            DeepSeekApiClient deepSeekApiClient,
            ConversationContextService conversationContextService,
            ObjectMapper objectMapper,
            VectorDbService vectorDbService,
            GuestSessionService guestSessionService,
            LearningMemoryService learningMemoryService,
            ChatHistoryService chatHistoryService,
            ChatAnswerCacheService chatAnswerCacheService,
            GroundingAuditService groundingAuditService,
            LearningSupervisorService learningSupervisorService,
            AiTeacherPreferencePolicy aiTeacherPreferencePolicy,
            @Qualifier("sseStreamExecutor") Executor sseStreamExecutor
    ) {
        this.ragRetrievalService = ragRetrievalService;
        this.deepSeekApiClient = deepSeekApiClient;
        this.conversationContextService = conversationContextService;
        this.objectMapper = objectMapper;
        this.vectorDbService = vectorDbService;
        this.guestSessionService = guestSessionService;
        this.learningMemoryService = learningMemoryService;
        this.chatHistoryService = chatHistoryService;
        this.chatAnswerCacheService = chatAnswerCacheService;
        this.groundingAuditService = groundingAuditService;
        this.learningSupervisorService = learningSupervisorService;
        this.aiTeacherPreferencePolicy = aiTeacherPreferencePolicy;
        this.sseStreamExecutor = sseStreamExecutor;
    }

    /**
     * Primary multi-turn chat stream (SseEmitter — Servlet-friendly, dedicated thread pool).
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@Valid @RequestBody StreamChatRequest request) {
        Optional<String> guestId = resolveAuthenticatedGuestId();
        if (guestId.isPresent() && !guestSessionService.tryConsumeChatTurn(guestId.get())) {
            return quotaExceededEmitter(guestSessionService.getChatQuota(guestId.get()));
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        sseStreamExecutor.execute(() -> runChatStream(emitter, request, resolveUserQuery(request)));

        emitter.onCompletion(() -> log.debug("SSE chat completed"));
        emitter.onTimeout(() -> {
            log.warn("SSE chat timeout");
            emitter.complete();
        });
        emitter.onError(e -> log.error("SSE chat error: {}", e.getMessage()));

        return emitter;
    }

    /**
     * RAG handout stream (GET + query) — SseEmitter with memory_status on first frame.
     */
    @GetMapping(value = "/rag-generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ragStreamGenerate(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "false") Boolean enableVoice
    ) {
        StreamChatRequest request = new StreamChatRequest(
                ragSystemPrompt(),
                List.of(new ChatMessageDto("user", query)),
                query,
                true,
                enableVoice,
                query,
                com.visionary.agent.AgentTaskType.RESOURCE_GENERATION.name(),
                null,
                null,
                null,
                null,
                "AUTO"
        );
        return streamChat(request);
    }

    @PostMapping("/retrieve-context")
    public ResponseEntity<Map<String, Object>> retrieveContext(
            @RequestBody Map<String, Object> request
    ) {
        String query = (String) request.get("query");
        String layer = request.get("layer") != null ? request.get("layer").toString() : null;
        com.visionary.rag.RagRetrievalResult result;
        if (layer != null && !layer.isBlank()) {
            com.visionary.rag.KnowledgeLayer knowledgeLayer =
                    com.visionary.rag.KnowledgeLayer.fromMetadata(layer);
            if (knowledgeLayer != null) {
                result = ragRetrievalService.retrieveForLayers(query, List.of(knowledgeLayer));
            } else {
                result = ragRetrievalService.retrieveForTask(
                        com.visionary.agent.AgentTaskType.RESOURCE_GENERATION, query);
            }
        } else {
            result = ragRetrievalService.retrieveForTask(
                    com.visionary.agent.AgentTaskType.RESOURCE_GENERATION, query);
        }
        return ResponseEntity.ok(Map.of(
                "context", result.groundedContextBlock(),
                "citations", result.citations(),
                "grounded", result.hasGroundedEvidence()
        ));
    }

    // ==================== stream pipeline ====================

    private void runChatStream(SseEmitter emitter, StreamChatRequest request, String userQuery) {
        try {
            emitter.send(SseEmitter.event()
                    .name(EVENT_STATUS)
                    .data(toJson(Map.of("phase", "ACCEPTED", "message", "已收到问题，正在选择合适的学习工具…"))));
            StreamPipeline pipeline = buildPipeline(request, userQuery);
            emitter.send(SseEmitter.event()
                    .name(EVENT_AGENT_TRACE)
                    .data(toJson(agentTracePayload(pipeline.plan(), "plan", "COMPLETED"))));

            emitter.send(SseEmitter.event()
                    .name(EVENT_MEMORY_STATUS)
                    .data(toJson(pipeline.context().memoryStatus())));

            if (request.ragEnabled()) {
                RagRetrievalResult rag = pipeline.ragResult();
                Map<String, Object> ragPayload = new LinkedHashMap<>();
                ragPayload.put("retrieved", rag != null && rag.hasGroundedEvidence());
                ragPayload.put("grounded", rag != null && rag.hasGroundedEvidence());
                ragPayload.put("ragStatus", resolveRagStatus(rag));
                ragPayload.put("retrievalMode", rag == null ? "NONE" : rag.retrievalMode());
                ragPayload.put("highAvailability", rag != null && rag.highAvailabilityFallback());
                ragPayload.put("citations", rag == null ? List.of() : rag.citations());
                emitter.send(SseEmitter.event()
                        .name(EVENT_RAG_CONTEXT)
                        .data(toJson(ragPayload)));
            }

            emitter.send(SseEmitter.event()
                    .name(EVENT_AGENT_TRACE)
                    .data(toJson(agentTracePayload(
                            pipeline.plan(),
                            pipeline.plan().requiresGrounding() ? "retrieve" : "teach",
                            "COMPLETED"
                    ))));

            Optional<String> reviewedAnswer = chatAnswerCacheService.find(userQuery, request.tutoringMode());
            if (reviewedAnswer.isPresent()) {
                sendReviewedAnswer(emitter, request, pipeline, userQuery, reviewedAnswer.get());
                return;
            }

            StringBuilder generated = new StringBuilder();
            AtomicReference<RagRetrievalResult> ragRef = new AtomicReference<>(pipeline.ragResult());
            Long persistedUserId = resolveAuthenticatedUserId().orElse(null);
            String lastUserContent = resolveLastUserContent(request.messages());
            deepSeekApiClient.streamChatMessages(
                    pipeline.context().systemPrompt(),
                    pipeline.messagesForLlm(),
                    token -> {
                        try {
                            generated.append(token);
                            sendContentChunk(emitter, token, request.voiceEnabled());
                        } catch (IOException e) {
                            completeWithError(emitter, e);
                        }
                    },
                    () -> {
                        chatAnswerCacheService.remember(
                                userQuery,
                                request.tutoringMode(),
                                generated.toString()
                        );
                        try {
                            auditGrounding(emitter, generated.toString(), ragRef.get());
                        } catch (IOException e) {
                            log.warn("Grounding audit event failed: {}", e.getMessage());
                        }
                        learningMemoryService.updateSessionMemory(
                                request.learningSessionId(),
                                generated.toString(),
                                request.emotionProfileSnapshot()
                        );
                        try {
                            emitter.send(SseEmitter.event()
                                    .name(EVENT_AGENT_TRACE)
                                    .data(toJson(agentTracePayload(pipeline.plan(), "update", "COMPLETED"))));
                        } catch (IOException e) {
                            log.debug("Agent trace completion event skipped: {}", e.getMessage());
                        }
                        if (request.learningSessionId() != null && persistedUserId != null) {
                            String assistantContent = generated.toString();
                            sseStreamExecutor.execute(() -> chatHistoryService.persistTurn(
                                    request.learningSessionId(),
                                    persistedUserId,
                                    lastUserContent,
                                    assistantContent
                            ));
                        }
                        completeSuccessfully(emitter);
                    },
                    error -> completeWithError(emitter, error)
            );

        } catch (Exception e) {
            completeWithError(emitter, e);
        }
    }

    private StreamPipeline buildPipeline(StreamChatRequest request, String userQuery) {
        LearningAgentPlan plan = learningSupervisorService.plan(request, userQuery);
        Long userId = resolveAuthenticatedUserId().orElse(null);
        String memoryBlock = learningMemoryService.buildMemoryPrompt(
                userId,
                request.learningSessionId(),
                request.studentProfileSnapshot(),
                request.emotionProfileSnapshot(),
                request.clientContext()
        );
        String systemPrompt = mergeSystemPrompt(request.systemPrompt(), memoryBlock)
                + "\n\n"
                + teachingResponseContract()
                + "\n\n"
                + aiTeacherPreferencePolicy.instruction(request.studentProfileSnapshot())
                + "\n\n"
                + learningSupervisorService.systemInstruction(plan)
                + "\n\n"
                + tutoringModeInstruction(request.tutoringMode(), plan.intent().name());
        ConversationContext context = conversationContextService.assemble(
                systemPrompt,
                request.messages() != null ? request.messages() : List.of()
        );
        if (context.memoryStatus().droppedMessages() > 0) {
            sseStreamExecutor.execute(() -> learningMemoryService.summarizeDroppedMessages(
                    request.learningSessionId(),
                    request.messages(),
                    context.memoryStatus().droppedMessages()
            ));
        }

        List<ChatMessageDto> llmMessages = new ArrayList<>(context.messages());
        String ragQuery = resolveRagQuery(request, userQuery);
        RagRetrievalResult knowledge = null;
        if (request.ragEnabled() && plan.requiresGrounding() && ragQuery != null && !ragQuery.isBlank()) {
            knowledge = ragRetrievalService.retrieveForLayers(ragQuery, plan.ragLayers());
            String ragBlock = truncateForModel(knowledge.groundedContextBlock(), MAX_RAG_CONTEXT_CHARS)
                    + "\n\n"
                    + knowledge.toCitationInstructionBlock()
                    + "\n\n[输出要求] 直接输出面向学生的教学回答。先给明确结论，再按必要步骤解释。"
                    + "禁止在正文中出现 citationId、引用编号、[cite-...] 或任何内部检索标记；"
                    + "不要输出 <thought>、内部推理或 JSON。";
            llmMessages = appendRagToLastUser(llmMessages, ragBlock);
        }

        return new StreamPipeline(context, llmMessages, knowledge, plan);
    }

    private void sendReviewedAnswer(
            SseEmitter emitter,
            StreamChatRequest request,
            StreamPipeline pipeline,
            String userQuery,
            String content
    ) throws IOException {
        emitter.send(SseEmitter.event()
                .name(EVENT_STATUS)
                .data(toJson(Map.of(
                        "phase", "REVIEWED_CACHE_HIT",
                        "message", "已完成知识检索，正在输出经审核的教学答案…"
                ))));

        for (String chunk : splitReviewedAnswer(content)) {
            sendContentChunk(emitter, chunk, request.voiceEnabled());
        }
        auditGrounding(emitter, content, pipeline.ragResult());
        emitter.send(SseEmitter.event()
                .name(EVENT_AGENT_TRACE)
                .data(toJson(agentTracePayload(pipeline.plan(), "update", "COMPLETED"))));

        Long persistedUserId = resolveAuthenticatedUserId().orElse(null);
        learningMemoryService.updateSessionMemory(
                request.learningSessionId(),
                content,
                request.emotionProfileSnapshot()
        );
        if (request.learningSessionId() != null && persistedUserId != null) {
            sseStreamExecutor.execute(() -> chatHistoryService.persistTurn(
                    request.learningSessionId(),
                    persistedUserId,
                    userQuery,
                    content
            ));
        }
        log.info("[ChatAnswerCache] reviewed answer served queryLen={} contentLen={}",
                userQuery == null ? 0 : userQuery.length(), content.length());
        completeSuccessfully(emitter);
    }

    private static List<String> splitReviewedAnswer(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = content.split("(?<=\\n\\n)");
        for (String paragraph : paragraphs) {
            if (!paragraph.isEmpty()) {
                chunks.add(paragraph);
            }
        }
        return chunks.isEmpty() ? List.of(content) : List.copyOf(chunks);
    }

    private static String truncateForModel(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "\n\n[检索内容已按相关性截断]";
    }

    private static List<ChatMessageDto> appendRagToLastUser(List<ChatMessageDto> messages, String ragBlock) {
        if (messages.isEmpty()) {
            return List.of(new ChatMessageDto("user", ragBlock));
        }
        List<ChatMessageDto> copy = new ArrayList<>(messages);
        int last = copy.size() - 1;
        ChatMessageDto tail = copy.get(last);
        if ("user".equalsIgnoreCase(tail.role())) {
            copy.set(last, new ChatMessageDto("user", tail.content() + "\n\n" + ragBlock));
        } else {
            copy.add(new ChatMessageDto("user", ragBlock));
        }
        return copy;
    }

    static String tutoringModeInstruction(String requestedMode, String intent) {
        String mode = requestedMode == null ? "AUTO" : requestedMode.trim().toUpperCase();
        return switch (mode) {
            case "HINT" -> "教学模式=提示：只给一个能推动下一步的提示或检查问题；禁止给最终答案、完整证明或完整代码。";
            case "STEP_BY_STEP" -> "教学模式=分步：本轮只讲一个步骤，然后提出一个具体问题并停止，等待学生回复后才能进入下一步；禁止提前展示最终答案。";
            case "DIRECT_ANSWER" -> "教学模式=答案：可以给出明确结论，但必须说明关键步骤、依据和自检方法；若处于未提交的正式考试场景，仍以考试防泄题规则为最高优先级。";
            default -> "教学模式=自动：根据当前场景（" + intent + "）选择提示、分步或直接解释；遇到解题和测评优先引导，概念查询可直接解释。";
        };
    }

    private String resolveUserQuery(StreamChatRequest request) {
        if (request.query() != null && !request.query().isBlank()) {
            return request.query().trim();
        }
        return resolveLastUserContent(request.messages());
    }

    private static String resolveLastUserContent(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessageDto m = messages.get(i);
            if (m != null && "user".equalsIgnoreCase(m.role()) && m.content() != null) {
                return m.content().trim();
            }
        }
        return "";
    }

    private void auditGrounding(SseEmitter emitter, String generated, RagRetrievalResult rag) throws IOException {
        var audit = groundingAuditService.audit(generated, rag);
        if (audit.isEmpty()) {
            return;
        }
        var result = audit.get();
        emitter.send(SseEmitter.event()
                .name(EVENT_GROUNDING_AUDIT)
                .data(toJson(groundingAuditService.toPayload(result))));
        if (result.needsReview()) {
            log.warn("[ChatGrounding] status={} lexical={} semantic={} msg={}",
                    result.status(), result.lexicalFaithfulnessScore(),
                    result.semanticFaithfulnessScore(), result.message());
        }
    }

    private static String resolveRagQuery(StreamChatRequest request, String userQuery) {
        if (request.ragQuery() != null && !request.ragQuery().isBlank()) {
            return request.ragQuery().trim();
        }
        return userQuery;
    }

    private static Optional<String> resolveAuthenticatedGuestId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details && details.isGuest()) {
            return Optional.of(details.getUsername());
        }
        return Optional.empty();
    }

    private static Optional<Long> resolveAuthenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CustomUserDetails details && details.isRegisteredUser()) {
            return Optional.of(details.getUserId());
        }
        return Optional.empty();
    }

    private static String mergeSystemPrompt(String basePrompt, String memoryBlock) {
        String base = basePrompt == null || basePrompt.isBlank()
                ? ragSystemPrompt()
                : basePrompt.trim();
        if (memoryBlock == null || memoryBlock.isBlank()) {
            return base;
        }
        return base + "\n\n" + memoryBlock;
    }

    private static String teachingResponseContract() {
        return """
                你是负责教学而不是堆砌资料的 AI 老师。回答必须遵守：
                1. 开头用 1-3 句话直接回答用户问题，不要让学生点击外部链接才能看到答案。
                2. 需要推导或操作时使用清晰的编号步骤；简单问题不要机械生成冗长模板。
                3. 公式、代码、例子与常见误区只在有助于理解时给出。
                4. 不得输出“点击查看答案”一类占位链接；练习题必须在本回答中包含可验证的答案和解析。
                5. 使用规范 Markdown，标题、列表、代码块必须各自独立成行。
                6. 证据不足或存在冲突时明确说明不确定性，不得编造教材、章节或引用。
                """;
    }

    private SseEmitter quotaExceededEmitter(GuestSessionService.GuestChatQuota quota) {
        SseEmitter emitter = new SseEmitter(5_000L);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", GuestChatQuotaExceededException.ERROR_CODE);
            payload.put("message", "游客免费对话次数已用完，请注册或登录后继续");
            payload.put("quota", quota);
            emitter.send(SseEmitter.event()
                    .name(EVENT_ERROR)
                    .data(objectMapper.writeValueAsString(payload)));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    private void sendContentChunk(SseEmitter emitter, String token, boolean needTts) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunk", token);
        if (needTts) {
            payload.put("need_tts", true);
        }
        emitter.send(SseEmitter.event()
                .name(EVENT_CONTENT)
                .data(objectMapper.writeValueAsString(payload)));
    }

    private void completeSuccessfully(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name(EVENT_COMPLETE).data("done"));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private void completeWithError(SseEmitter emitter, Throwable error) {
        log.error("Stream failed: {}", error.getMessage());
        try {
            emitter.send(SseEmitter.event().name(EVENT_ERROR).data(error.getMessage()));
            emitter.completeWithError(error);
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private String toJson(Object value) throws IOException {
        return objectMapper.writeValueAsString(value);
    }

    private String resolveRagStatus(RagRetrievalResult rag) {
        if (rag != null && rag.highAvailabilityFallback()) {
            return rag.hasGroundedEvidence() ? "HA_FALLBACK" : "DEGRADED";
        }
        if (!vectorDbService.isAvailable() && !ragRetrievalService.isAvailable()) {
            return "UNAVAILABLE";
        }
        if (rag == null || !rag.hasGroundedEvidence()) {
            return "DEGRADED";
        }
        return "GROUNDED";
    }

    private static String ragSystemPrompt() {
        return """
                You are an adaptive tutoring content generator.
                Produce a concise markdown handout with examples and checkpoints.
                Use the provided RAG context to ground your explanations.
                Never include citationId, [cite-...] markers, or internal retrieval metadata in the student-facing output.
                """;
    }

    private Map<String, Object> agentTracePayload(LearningAgentPlan plan, String currentAction, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", plan.intent().name());
        payload.put("goal", plan.learnerGoal());
        payload.put("tools", plan.tools());
        payload.put("steps", plan.visibleSteps());
        payload.put("currentAction", currentAction);
        payload.put("status", status);
        return payload;
    }

    private record StreamPipeline(
            ConversationContext context,
            List<ChatMessageDto> messagesForLlm,
            RagRetrievalResult ragResult,
            LearningAgentPlan plan
    ) {
    }
}
