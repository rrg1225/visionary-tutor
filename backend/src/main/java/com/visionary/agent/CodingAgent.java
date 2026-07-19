package com.visionary.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.core.AgentContext;
import com.visionary.agent.core.AgentResult;
import com.visionary.agent.core.AgentTask;
import com.visionary.agent.core.BaseSpecialistAgent;
import com.visionary.agent.core.SharedBlackboard;
import com.visionary.agent.core.Tool;
import com.visionary.agent.core.ToolContext;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.config.AgentOrchestrationProperties;
import com.visionary.service.CodeSandboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class CodingAgent extends BaseSpecialistAgent {

    private static final String BB_CITATIONS = "CodingAgent_citations";
    private static final String BB_USED_RAG = "CodingAgent_usedRAG";
    private static final String BB_REVISION_APPLIED = "CodingAgent_revisionApplied";
    private static final String BB_TASK_INPUT = "CodingAgent_taskInput";
    private static final String BB_TOPIC = "CodingAgent_topic";
    private static final String BB_SANDBOX_STATUS = "CodingAgent_sandboxStatus";
    private static final String BB_SANDBOX_PASSED = "CodingAgent_sandboxPassed";
    private static final String BB_SANDBOX_TOTAL = "CodingAgent_sandboxTotal";

    private final ObjectMapper objectMapper;
    private final DeepSeekApiClient deepSeekApiClient;
    private final CodeSandboxService codeSandboxService;

    public CodingAgent(ObjectMapper objectMapper, DeepSeekApiClient deepSeekApiClient,
                       CodeSandboxService codeSandboxService, AgentOrchestrationProperties orchestrationProps) {
        this.objectMapper = objectMapper;
        this.deepSeekApiClient = deepSeekApiClient;
        this.codeSandboxService = codeSandboxService;
        setOrchestrationProperties(orchestrationProps);
    }

    @Override
    public String getRole() {
        return "CodingAgent";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of("RAGRetrieveTool", "ArtifactPersistTool", "CodeSandboxService");
    }

    @Override
    protected String getSystemPrompt() {
        return """
                你是CodingAgent，专门生成PyTorch深度学习代码实操案例。

                严格规则：
                1. 所有代码必须可运行（Python 3.8+, PyTorch 2.0+）
                2. 代码包含完整注释，解释关键步骤
                3. 提供shape调试打印，帮助理解tensor变化
                4. 处理常见错误（如维度不匹配、设备问题）
                5. 难度分级：
                   - GUIDED: 填空式代码，80%%预填，学生补20%%
                   - CHALLENGE: 给出要求，学生独立实现
                   - PROJECT: 综合项目，多模块协作

                输出Markdown格式，代码块标注python。
                """;
    }

    @Override
    protected String buildRagQuery(AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = resolveTopic(task, blackboard);
        String revisionInstruction = Optional.ofNullable(task.input().get("revisionInstruction"))
                .map(Object::toString)
                .orElse("");

        blackboard.put(BB_TASK_INPUT, task.input());
        blackboard.put(BB_TOPIC, topic);
        blackboard.put(BB_REVISION_APPLIED, !revisionInstruction.isBlank());

        return (topic + " code practice " + revisionInstruction).trim();
    }

    @Override
    protected String buildLlmPrompt(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("code practice");

        String difficulty = String.valueOf(task.input().getOrDefault("difficulty", "GUIDED"));
        boolean includeSolutions = Boolean.TRUE.equals(task.input().get("includeSolutions"));

        String profile = context.blackboard().getLearnerProfileSnapshot();
        if (profile == null || profile.isBlank()) {
            profile = String.valueOf(task.input().getOrDefault(
                    "learnerProfileSnapshot",
                    task.input().getOrDefault("learnerProfile", "{}")
            ));
        }

        String evidence = ragContext.isBlank()
                ? "No optional knowledge-base context. Generate the complete runnable exercise from model knowledge."
                : ragContext;
        String revisionBlock = AgentPromptSupport.revisionBlock(task);

        return """
                生成"%s"的%s难度代码实操

                学生画像：%s
                %s

                参考资料：
                %s

                请输出完整代码实操案例。
                """.formatted(
                topic,
                difficulty,
                profile.length() > 300 ? profile.substring(0, 300) : profile,
                includeSolutions ? "（需要包含完整解答）" : "（不包含解答，让学生独立完成）",
                evidence
        ) + revisionBlock;
    }

    @Override
    protected String performLlmGeneration(String systemPrompt, String userPrompt, AgentTask task, AgentContext context) {
        if (deepSeekApiClient == null || !deepSeekApiClient.isConfigured()) {
            throw new IllegalStateException("DeepSeek API not configured");
        }
        String generated = null;
        try {
            generated = deepSeekApiClient.chat(systemPrompt, userPrompt, false);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // 执行沙箱验证
        SharedBlackboard blackboard = context.blackboard();
        CodeSandboxService.SandboxReport sandboxReport = codeSandboxService != null
                ? codeSandboxService.validateMarkdown(generated)
                : new CodeSandboxService.SandboxReport("SKIPPED", 0, 0, "CodeSandboxService unavailable.", List.of());

        blackboard.put(BB_SANDBOX_STATUS, sandboxReport.status());
        blackboard.put(BB_SANDBOX_PASSED, sandboxReport.passedSnippets());
        blackboard.put(BB_SANDBOX_TOTAL, sandboxReport.totalSnippets());

        return generated + "\n\n" + sandboxReport.toMarkdown();
    }

    @Override
    protected String buildFallbackContent(AgentTask task, String ragContext, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("code practice");

        Object difficultyObj = task.input().get("difficulty");
        if (difficultyObj != null && !difficultyObj.toString().isBlank()) {
            return toolFallbackTemplate(topic, difficultyObj.toString(),
                    Boolean.TRUE.equals(task.input().get("includeSolutions")));
        }

        return """
                # Code practice: %s

                Evidence excerpt:
                %s

                ```python
                def conv2d_output_size(input_size, kernel_size, padding=0, stride=1):
                    return (input_size + 2 * padding - kernel_size) // stride + 1

                def test_conv2d_output_size():
                    assert conv2d_output_size(28, 3, padding=1, stride=1) == 28
                    assert conv2d_output_size(32, 5, padding=0, stride=1) == 28
                    assert conv2d_output_size(32, 3, padding=1, stride=2) == 16

                if __name__ == "__main__":
                    test_conv2d_output_size()
                    print("all tests passed")
                ```
                """.formatted(
                topic,
                ragContext.isBlank()
                        ? "No optional knowledge-base context; generate the complete runnable exercise from model knowledge."
                        : ragContext.substring(0, Math.min(800, ragContext.length()))
        );
    }

    @Override
    protected AgentResult buildResult(String generatedContent, List<String> citations, boolean usedRag,
                                      AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = Optional.ofNullable(blackboard.get(BB_TOPIC))
                .map(Object::toString)
                .orElse("code practice");
        boolean revisionApplied = Boolean.TRUE.equals(blackboard.get(BB_REVISION_APPLIED));

        @SuppressWarnings("unchecked")
        Map<String, Object> taskInput = blackboard.get(BB_TASK_INPUT) instanceof Map<?, ?> map
                ? (Map<String, Object>) map
                : Map.of();

        persistArtifact(taskInput, context, topic, generatedContent);

        blackboard.put(BB_CITATIONS, new ArrayList<>(citations));
        blackboard.put(BB_USED_RAG, usedRag);

        blackboard.addTrace(new SharedBlackboard.AgentRunTrace(
                getRole(),
                "rag-llm-pipeline",
                "Code practice for " + topic + " with sandbox validation",
                Instant.now()
        ));

        return new AgentResult(
                true,
                generatedContent,
                citations,
                Map.of(
                        "artifactType", "CODE_PRACTICE",
                        "usedRAG", usedRag,
                        "agentLoop", "standard-pipeline",
                        "revisionApplied", revisionApplied,
                        "sandboxStatus", String.valueOf(blackboard.get(BB_SANDBOX_STATUS)),
                        "sandboxPassed", blackboard.get(BB_SANDBOX_PASSED),
                        "sandboxTotal", blackboard.get(BB_SANDBOX_TOTAL)
                ),
                List.of()
        );
    }

    private String resolveTopic(AgentTask task, SharedBlackboard blackboard) {
        if (blackboard.getCurrentTopic() != null && !blackboard.getCurrentTopic().isBlank()) {
            return blackboard.getCurrentTopic();
        }
        Object topic = task.input().get("topic");
        if (topic != null && !topic.toString().isBlank()) {
            return topic.toString();
        }
        return "code practice";
    }

    private void persistArtifact(
            Map<String, Object> taskInput,
            AgentContext context,
            String topic,
            String content
    ) {
        Tool persistTool = context.tools().get("ArtifactPersistTool");
        if (persistTool == null || !taskInput.containsKey("learningSessionId")) {
            return;
        }

        Object sessionRaw = taskInput.get("learningSessionId");
        Long learningSessionId = sessionRaw instanceof Number number
                ? number.longValue()
                : Long.parseLong(sessionRaw.toString());

        ObjectNode args = objectMapper.createObjectNode();
        args.put("learningSessionId", learningSessionId);
        args.put("type", "CODE_PRACTICE");
        args.put("title", topic + " code practice");
        args.put("content", content);
        persistTool.execute(args, new ToolContext(context.blackboard(), context.runId(), Map.of()));
    }

    private String toolFallbackTemplate(String topic, String difficulty, boolean includeSolutions) {
        String solutionSection = includeSolutions ? """
                ### 参考答案（完整实现）
                ```python
                # 此处应有完整实现代码
                # 配置DeepSeek API后可获取
                ```
                """ : """
                ### 验证标准
                你的代码应该能够：
                - 正确初始化模型
                - 处理输入数据并输出预期shape
                - 无明显运行时错误
                """;

        return """
                # %s 代码实操（%s难度）

                ## 任务目标
                实现%s的核心代码，理解其工作原理。

                ## 环境准备
                ```bash
                pip install torch torchvision matplotlib
                ```

                ## 代码框架
                ```python
                import torch
                import torch.nn as nn
                print(f"PyTorch version: {torch.__version__}")
                ```

                %s

                > 提示：配置DeepSeek API后可获取完整可运行代码。
                """.formatted(topic, difficulty, topic, solutionSection);
    }
}
