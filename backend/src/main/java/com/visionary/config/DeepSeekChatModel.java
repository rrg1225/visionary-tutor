package com.visionary.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek API 适配器，实现 Langchain4j 的 ChatLanguageModel 接口
 * 使 DeepSeek 可以被 AiServices 使用，支持 ReAct 模式的多智能体架构
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeepSeekChatModel implements ChatLanguageModel {

    private final ObjectMapper objectMapper;

    @Value("${deepseek.api.key:${DEEPSEEK_API_KEY:}}")
    private String apiKey;

    @Value("${deepseek.api.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${deepseek.model:deepseek-chat}")
    private String model;

    private volatile OkHttpClient httpClient;

    private OkHttpClient getHttpClient() {
        if (httpClient == null) {
            synchronized (this) {
                if (httpClient == null) {
                    httpClient = new OkHttpClient.Builder()
                        .connectTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(120, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .build();
                }
            }
        }
        return httpClient;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        return generate(messages, null, null);
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages, List<ToolSpecification> toolSpecifications) {
        return generate(messages, toolSpecifications, null);
    }

    public Response<AiMessage> generate(List<ChatMessage> messages,
                                        List<ToolSpecification> toolSpecifications,
                                        ToolSpecification toolThatMustBeExecuted) {
        if (!isConfigured()) {
            log.warn("[DeepSeekChatModel] API key not configured, returning fallback response");
            return Response.from(AiMessage.from("[系统未配置DeepSeek API密钥，无法执行ReAct工作流]"),
                new TokenUsage(0, 0), null);
        }

        try {
            ObjectNode requestBody = buildRequestBody(messages, toolSpecifications, toolThatMustBeExecuted);
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.parse("application/json; charset=utf-8")))
                .build();

            try (okhttp3.Response response = getHttpClient().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "empty";
                    log.error("[DeepSeekChatModel] API error: {} - {}", response.code(), errorBody);
                    throw new IOException("DeepSeek API error: " + response.code() + " " + errorBody);
                }

                String responseBody = response.body().string();
                return parseResponse(responseBody);
            }

        } catch (Exception e) {
            log.error("[DeepSeekChatModel] Generation failed: {}", e.getMessage(), e);
            throw new RuntimeException("DeepSeek API call failed: " + e.getMessage(), e);
        }
    }

    private ObjectNode buildRequestBody(List<ChatMessage> messages,
                                        List<ToolSpecification> toolSpecifications,
                                        ToolSpecification toolThatMustBeExecuted) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);

        // 转换消息
        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (ChatMessage msg : messages) {
            ObjectNode msgNode = convertMessage(msg);
            if (msgNode != null) {
                messagesArray.add(msgNode);
            }
        }
        requestBody.set("messages", messagesArray);

        // 添加工具定义
        if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
            ArrayNode toolsArray = objectMapper.createArrayNode();
            for (ToolSpecification spec : toolSpecifications) {
                toolsArray.add(convertToolSpecification(spec));
            }
            requestBody.set("tools", toolsArray);

            // 强制工具调用
            if (toolThatMustBeExecuted != null) {
                ObjectNode toolChoice = objectMapper.createObjectNode();
                toolChoice.put("type", "function");
                ObjectNode functionNode = objectMapper.createObjectNode();
                functionNode.put("name", toolThatMustBeExecuted.name());
                toolChoice.set("function", functionNode);
                requestBody.set("tool_choice", toolChoice);
            } else {
                // 让模型自主决定是否调用工具
                requestBody.put("tool_choice", "auto");
            }
        }

        // 其他参数
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 4096);
        requestBody.put("stream", false);

        return requestBody;
    }

    private ObjectNode convertMessage(ChatMessage message) {
        ObjectNode node = objectMapper.createObjectNode();

        if (message instanceof SystemMessage) {
            node.put("role", "system");
            node.put("content", ((SystemMessage) message).text());
        } else if (message instanceof UserMessage) {
            node.put("role", "user");
            node.put("content", ((UserMessage) message).singleText());
        } else if (message instanceof AiMessage aiMsg) {
            node.put("role", "assistant");

            if (aiMsg.hasToolExecutionRequests()) {
                // 处理工具调用请求
                ArrayNode toolCalls = objectMapper.createArrayNode();
                for (ToolExecutionRequest toolReq : aiMsg.toolExecutionRequests()) {
                    ObjectNode toolCall = objectMapper.createObjectNode();
                    toolCall.put("id", toolReq.id());
                    toolCall.put("type", "function");

                    ObjectNode function = objectMapper.createObjectNode();
                    function.put("name", toolReq.name());
                    function.put("arguments", toolReq.arguments());

                    toolCall.set("function", function);
                    toolCalls.add(toolCall);
                }
                node.set("tool_calls", toolCalls);

                // 如果有内容也保留
                if (aiMsg.text() != null && !aiMsg.text().isEmpty()) {
                    node.put("content", aiMsg.text());
                }
            } else {
                node.put("content", aiMsg.text());
            }
        } else if (message instanceof ToolExecutionResultMessage toolResult) {
            node.put("role", "tool");
            node.put("tool_call_id", toolResult.id());
            node.put("content", toolResult.text());
        } else {
            log.warn("[DeepSeekChatModel] Unknown message type: {}", message.getClass());
            return null;
        }

        return node;
    }

    private ObjectNode convertToolSpecification(ToolSpecification spec) {
        ObjectNode tool = objectMapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode function = objectMapper.createObjectNode();
        function.put("name", spec.name());
        function.put("description", spec.description());

        if (spec.parameters() != null) {
            function.set("parameters", convertToolParameters(spec.parameters()));
        } else {
            // 默认参数结构
            ObjectNode defaultParams = objectMapper.createObjectNode();
            defaultParams.put("type", "object");
            defaultParams.set("properties", objectMapper.createObjectNode());
            function.set("parameters", defaultParams);
        }

        tool.set("function", function);
        return tool;
    }

    private ObjectNode convertToolParameters(JsonObjectSchema parameters) {
        return (ObjectNode) convertJsonSchemaElement(parameters);
    }

    private JsonNode convertJsonSchemaElement(JsonSchemaElement element) {
        ObjectNode node = objectMapper.createObjectNode();
        if (element instanceof JsonObjectSchema objectSchema) {
            node.put("type", "object");
            putDescription(node, objectSchema.description());
            ObjectNode properties = objectMapper.createObjectNode();
            if (objectSchema.properties() != null) {
                objectSchema.properties().forEach((name, property) ->
                        properties.set(name, convertJsonSchemaElement(property)));
            }
            node.set("properties", properties);
            ArrayNode required = objectMapper.createArrayNode();
            if (objectSchema.required() != null) {
                objectSchema.required().forEach(required::add);
            }
            node.set("required", required);
            node.put("additionalProperties", objectSchema.additionalProperties() != null
                    ? objectSchema.additionalProperties()
                    : false);
        } else if (element instanceof JsonIntegerSchema integerSchema) {
            node.put("type", "integer");
            putDescription(node, integerSchema.description());
        } else if (element instanceof JsonNumberSchema numberSchema) {
            node.put("type", "number");
            putDescription(node, numberSchema.description());
        } else if (element instanceof JsonBooleanSchema booleanSchema) {
            node.put("type", "boolean");
            putDescription(node, booleanSchema.description());
        } else if (element instanceof JsonEnumSchema enumSchema) {
            node.put("type", "string");
            putDescription(node, enumSchema.description());
            ArrayNode values = node.putArray("enum");
            if (enumSchema.enumValues() != null) {
                enumSchema.enumValues().forEach(values::add);
            }
        } else if (element instanceof JsonStringSchema stringSchema) {
            node.put("type", "string");
            putDescription(node, stringSchema.description());
        } else {
            node.put("type", "string");
        }
        return node;
    }

    private void putDescription(ObjectNode node, String description) {
        if (description != null && !description.isBlank()) {
            node.put("description", description);
        }
    }

    private Response<AiMessage> parseResponse(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);

        if (!root.has("choices") || root.path("choices").isEmpty()) {
            log.error("[DeepSeekChatModel] Invalid response: {}", responseBody);
            throw new IOException("Invalid response from DeepSeek API");
        }

        JsonNode choice = root.path("choices").get(0);
        JsonNode message = choice.path("message");

        AiMessage aiMessage;

        // 检查是否有工具调用
        if (message.has("tool_calls") && !message.path("tool_calls").isEmpty()) {
            List<ToolExecutionRequest> toolRequests = new ArrayList<>();
            for (JsonNode toolCall : message.path("tool_calls")) {
                JsonNode function = toolCall.path("function");
                toolRequests.add(ToolExecutionRequest.builder()
                    .id(toolCall.path("id").asText())
                    .name(function.path("name").asText())
                    .arguments(function.path("arguments").asText())
                    .build());
            }
            aiMessage = AiMessage.from(toolRequests);
        } else {
            String content = message.path("content").asText();
            aiMessage = AiMessage.from(content);
        }

        // 解析token用量
        JsonNode usage = root.path("usage");
        TokenUsage tokenUsage = new TokenUsage(
            usage.path("prompt_tokens").asInt(),
            usage.path("completion_tokens").asInt()
        );

        return Response.from(aiMessage, tokenUsage, null);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank() && !"change-me".equals(apiKey);
    }
}
