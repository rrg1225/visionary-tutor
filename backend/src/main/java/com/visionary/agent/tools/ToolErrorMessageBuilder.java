package com.visionary.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具错误消息构建器 - 为 LLM 生成可操作的错误反馈。
 * <p>
 * 当工具执行失败时，返回友好的文本让 LLM 能够理解错误并自主决策下一步行动。
 */
@Slf4j
public class ToolErrorMessageBuilder {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 错误类型枚举
     */
    public enum ErrorType {
        LLM_TIMEOUT("LLM_TIMEOUT", "大模型调用超时"),
        RAG_EMPTY("RAG_EMPTY", "知识库检索为空"),
        AGENT_EXECUTION_FAILED("AGENT_EXECUTION_FAILED", "Agent 执行失败"),
        EXTERNAL_API_ERROR("EXTERNAL_API_ERROR", "外部 API 错误"),
        RATE_LIMIT("RATE_LIMIT", "请求频率限制"),
        NETWORK_ERROR("NETWORK_ERROR", "网络连接错误"),
        UNKNOWN_ERROR("UNKNOWN_ERROR", "未知错误");

        private final String code;
        private final String description;

        ErrorType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() { return code; }
        public String getDescription() { return description; }
    }

    /**
     * 构建 LLM 友好的错误消息
     *
     * @param toolName 工具名称
     * @param errorType 错误类型
     * @param originalError 原始错误信息
     * @param topic 当前主题
     * @return 格式化的错误反馈文本
     */
    public static String buildErrorResponse(String toolName, ErrorType errorType,
                                            String originalError, String topic) {
        String suggestion = getSuggestionForError(errorType, toolName);

        ObjectNode result = objectMapper.createObjectNode();
        result.put("status", "TOOL_FAILED");
        result.put("tool", toolName);
        result.put("errorType", errorType.getCode());
        result.put("errorDescription", errorType.getDescription());
        result.put("originalError", truncate(originalError, 200));
        result.put("topic", topic);
        result.put("timestamp", java.time.Instant.now().toString());

        // LLM 可读的反馈文本
        String llmFeedback = String.format(
            "Action failed: %s tool encountered %s. " +
            "Original error: %s. " +
            "Suggestion: %s",
            toolName,
            errorType.getDescription(),
            truncate(originalError, 100),
            suggestion
        );
        result.put("llmFeedback", llmFeedback);
        result.put("suggestionForLLM", suggestion);

        // 添加可操作的建议
        result.put("canRetry", canRetry(errorType));
        result.put("shouldTryAlternative", shouldTryAlternative(errorType));

        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return llmFeedback;
        }
    }

    /**
     * 构建特定错误类型的响应
     */
    public static String timeoutError(String toolName, String topic) {
        return buildErrorResponse(toolName, ErrorType.LLM_TIMEOUT,
            "Request timed out after waiting for LLM response", topic);
    }

    public static String ragEmptyError(String toolName, String topic) {
        return buildErrorResponse(toolName, ErrorType.RAG_EMPTY,
            "No relevant documents found in knowledge base for topic: " + topic, topic);
    }

    public static String agentExecutionError(String toolName, String agentError, String topic) {
        return buildErrorResponse(toolName, ErrorType.AGENT_EXECUTION_FAILED, agentError, topic);
    }

    public static String apiError(String toolName, String apiError, String topic) {
        return buildErrorResponse(toolName, ErrorType.EXTERNAL_API_ERROR, apiError, topic);
    }

    public static String rateLimitError(String toolName, String topic) {
        return buildErrorResponse(toolName, ErrorType.RATE_LIMIT,
            "Rate limit exceeded, please try again later", topic);
    }

    public static String networkError(String toolName, String networkError, String topic) {
        return buildErrorResponse(toolName, ErrorType.NETWORK_ERROR, networkError, topic);
    }

    public static String unknownError(String toolName, String error, String topic) {
        return buildErrorResponse(toolName, ErrorType.UNKNOWN_ERROR, error, topic);
    }

    /**
     * 获取针对特定错误的建议
     */
    private static String getSuggestionForError(ErrorType errorType, String toolName) {
        return switch (errorType) {
            case LLM_TIMEOUT ->
                "The LLM service is currently slow. Please consider: 1) Simplifying the request, 2) Trying a different tool, or 3) Proceeding with partial information.";
            case RAG_EMPTY ->
                "No knowledge base evidence found. Please: 1) Try generating content based on general knowledge (mark as ungrounded), 2) Use a simpler tool like generate_lecture_handout, or 3) Ask the student to provide more context.";
            case AGENT_EXECUTION_FAILED ->
                "The specialist agent failed to execute. Please: 1) Try the same tool again, 2) Use an alternative tool for similar functionality, or 3) Skip this resource type and continue with others.";
            case EXTERNAL_API_ERROR ->
                "External service is unavailable. Please: 1) Retry with the same parameters, 2) Use offline fallback tools, or 3) Mark this resource for manual generation later.";
            case RATE_LIMIT ->
                "API rate limit reached. Please: 1) Wait and retry, 2) Reduce the number of parallel tool calls, or 3) Use only essential tools for now.";
            case NETWORK_ERROR ->
                "Network connectivity issue. Please: 1) Retry the same request, 2) Use tools that don't require external calls, or 3) Continue with local processing.";
            case UNKNOWN_ERROR ->
                "An unexpected error occurred. Please: 1) Try the same tool again, 2) Try an alternative approach, or 3) Report this issue and proceed with available resources.";
        };
    }

    private static boolean canRetry(ErrorType errorType) {
        return switch (errorType) {
            case LLM_TIMEOUT, RATE_LIMIT, NETWORK_ERROR -> true;
            case RAG_EMPTY, AGENT_EXECUTION_FAILED, EXTERNAL_API_ERROR, UNKNOWN_ERROR -> false;
        };
    }

    private static boolean shouldTryAlternative(ErrorType errorType) {
        return switch (errorType) {
            case LLM_TIMEOUT, RAG_EMPTY, AGENT_EXECUTION_FAILED -> true;
            case RATE_LIMIT, NETWORK_ERROR, EXTERNAL_API_ERROR, UNKNOWN_ERROR -> true;
        };
    }

    private static String truncate(String str, int maxLength) {
        if (str == null) return "";
        return str.length() <= maxLength ? str : str.substring(0, maxLength) + "...";
    }
}
