package com.visionary.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.visionary.agent.core.*;
import com.visionary.client.DeepSeekApiClient;
import com.visionary.config.AgentOrchestrationProperties;
import com.visionary.exception.AiProviderException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * PlannerAgent - 混合架构智能规划器。
 * <p>
 * 根据 LearnerProfile 动态输出任务执行清单（JSON格式），
 * 包含需要的 Specialist 工具列表及其依赖关系。
 * <p>
 * 支持的学习风格：
 * - 视觉型：优先本地演示动画(VisualizationAgent)与导图(MindMapAgent)
 * - 文字型：优先讲义(DocAgent)、阅读(ReadingAgent)、测验(QuizAgent)
 * - 实践型：优先代码实操(CodingAgent)、路径规划(PathAgent)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerAgent implements Agent {

    private final DeepSeekApiClient deepSeekApiClient;
    private final ObjectMapper objectMapper;
    private final AgentOrchestrationProperties orchestrationProps;

    /**
     * 最大规划重试次数
     */
    private static final int MAX_PLANNING_RETRIES = 2;

    @Override
    public String getRole() {
        return "PlannerAgent";
    }

    @Override
    public Set<String> getSupportedTools() {
        return Set.of("RAGRetrieveTool", "ProfileMergeTool");
    }

    @Override
    public AgentResult execute(AgentTask task, AgentContext context) {
        SharedBlackboard blackboard = context.blackboard();
        String topic = (String) task.input().getOrDefault("topic", "未知主题");
        String profile = String.valueOf(task.input().getOrDefault("learnerProfileSnapshot", "暂无画像"));
        String weakPoints = String.valueOf(task.input().getOrDefault("weakPointsSnapshot", "暂无薄弱点"));
        String userQuestion = String.valueOf(task.input().getOrDefault("userQuestion", ""));

        Tool profileTool = context.tools().get("ProfileMergeTool");
        if (profileTool != null) {
            ObjectNode mergeArgs = objectMapper.createObjectNode();
            mergeArgs.put("learnerProfileSnapshot", profile);
            mergeArgs.put("weakPointsSnapshot", weakPoints);
            profileTool.execute(mergeArgs, new ToolContext(blackboard, context.runId(), Map.of()));
        } else {
            blackboard.updateProfileSnapshot(profile);
            blackboard.put("weakPointsSnapshot", weakPoints);
        }

        // 尝试生成动态规划（带重试）
        DynamicExecutionPlan plan = generateDynamicPlanWithRetry(topic, profile, weakPoints, userQuestion);

        blackboard.setCurrentTopic(topic);
        blackboard.addTrace(new SharedBlackboard.AgentRunTrace(
                getRole(), "planned", "Generated dynamic plan for " + topic + " with " + plan.getTasks().size() + " tasks",
                java.time.Instant.now()));

        // 将动态规划结果放入黑板，供后续步骤使用
        blackboard.put("planner_dynamic_execution_plan", plan);
        blackboard.put("planner_selected_agents", plan.getSelectedAgents());
        blackboard.put("planner_learning_style", plan.getDetectedLearningStyle());

        // 构建结果元数据
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("plan", plan.getPlanSummary());
        metadata.put("topic", topic);
        metadata.put("dynamicPlan", plan);
        metadata.put("isDynamicPlan", true);
        metadata.put("detectedLearningStyle", plan.getDetectedLearningStyle());
        metadata.put("selectedAgents", plan.getSelectedAgents());
        metadata.put("taskDependencies", plan.getTaskDependencies());

        AgentNegotiationProtocol.publishExecutionPlan(context, plan.getSelectedAgents(), plan.getPlanSummary());

        // 第一个任务的交接
        String firstAgent = plan.getTasks().isEmpty() ? "DocAgent" : plan.getTasks().get(0).getAgentRole();

        return new AgentResult(
                true,
                plan.getPlanSummary(),
                List.of(),
                metadata,
                List.of(new AgentHandoff(firstAgent, Map.of(
                        "plan", plan.getPlanSummary(),
                        "dynamicPlan", plan,
                        "topic", topic
                )))
        );
    }

    /**
     * 带重试的动态规划生成
     */
    private DynamicExecutionPlan generateDynamicPlanWithRetry(String topic, String profile, String weakPoints, String userQuestion) {
        for (int attempt = 0; attempt <= MAX_PLANNING_RETRIES; attempt++) {
            try {
                if (deepSeekApiClient != null && deepSeekApiClient.isConfigured()) {
                    DynamicExecutionPlan plan = generateDynamicExecutionPlan(topic, profile, weakPoints, userQuestion);
                    if (plan != null && plan.isValid()) {
                        log.info("[PlannerAgent] 动态规划成功 (attempt={}): {} 个任务, 学习风格={}",
                                attempt, plan.getTasks().size(), plan.getDetectedLearningStyle());
                        return plan;
                    }
                    log.warn("[PlannerAgent] 动态规划结果无效，尝试重试 (attempt={})", attempt);
                }
            } catch (AiProviderException e) {
                log.warn("[PlannerAgent] AI服务调用失败 (attempt={}): {}", attempt, e.getMessage());
            } catch (Exception e) {
                log.warn("[PlannerAgent] 动态规划系统异常 (attempt={}): {}", attempt, e.getMessage());
            }
        }

        // 所有尝试失败，返回默认全量规划
        log.warn("[PlannerAgent] 所有动态规划尝试失败，回退到默认全量规划");
        return buildFallbackPlan(topic);
    }

    /**
     * 生成动态任务执行清单（JSON格式）- 增强版，包含完整验证
     */
    private DynamicExecutionPlan generateDynamicExecutionPlan(String topic, String profile, String weakPoints, String userQuestion) throws Exception {
        String systemPrompt = buildDynamicPlanningSystemPrompt();
        String userPrompt = buildDynamicPlanningUserPrompt(topic, profile, weakPoints, userQuestion);

        log.info("[PlannerAgent] 发送规划请求: topic='{}'", topic);

        String response = deepSeekApiClient.chat(systemPrompt, userPrompt, false);

        if (response == null || response.isBlank()) {
            throw new IllegalStateException("Empty response from LLM");
        }

        // 解析 JSON 响应
        JsonNode root = parseJsonResponse(response);
        if (root == null) {
            log.error("[PlannerAgent] 无法解析响应: {}", truncate(response, 500));
            throw new IllegalStateException("Failed to parse planner response as JSON");
        }

        // 验证必要字段
        if (!root.has("learningStyle") || !root.has("tasks")) {
            log.error("[PlannerAgent] 缺少必要字段 (learningStyle/tasks)");
            throw new IllegalStateException("Response missing required fields: learningStyle, tasks");
        }

        // 构建动态执行计划
        DynamicExecutionPlan plan = new DynamicExecutionPlan();
        plan.setTopic(topic);

        // 检测并验证学习风格
        String detectedStyle = root.path("learningStyle").asText("balanced").toLowerCase();
        if (!isValidLearningStyle(detectedStyle)) {
            log.warn("[PlannerAgent] 无效的学习风格 '{}', 回退到 balanced", detectedStyle);
            detectedStyle = "balanced";
        }
        plan.setDetectedLearningStyle(detectedStyle);
        plan.setPlanSummary(root.path("planSummary").asText("基于" + detectedStyle + "学习风格的动态规划"));

        // 解析任务列表
        ArrayNode tasksNode = (ArrayNode) root.path("tasks");
        List<ExecutionTask> tasks = new ArrayList<>();
        Map<String, List<String>> dependencies = new HashMap<>();

        if (tasksNode == null || tasksNode.isEmpty()) {
            log.warn("[PlannerAgent] 任务列表为空");
            throw new IllegalStateException("Empty tasks array in response");
        }

        Set<String> usedTaskIds = new HashSet<>();

        for (JsonNode taskNode : tasksNode) {
            ExecutionTask task = parseExecutionTask(taskNode, usedTaskIds);
            if (task != null) {
                tasks.add(task);
                dependencies.put(task.getTaskId(), task.getDependencies());
            }
        }

        // 验证任务列表
        if (tasks.isEmpty()) {
            throw new IllegalStateException("No valid tasks parsed from response");
        }

        // 自动修复依赖关系（确保引用的 taskId 存在）
        validateAndFixDependencies(tasks, dependencies);

        // 确保 CriticAgent 存在（用于质量控制）
        ensureCriticAgentExists(tasks, dependencies, usedTaskIds);

        plan.setTasks(tasks);
        plan.setTaskDependencies(dependencies);

        // 提取选中的 Agents（排除 CriticAgent 的重复）
        Set<String> selectedAgents = tasks.stream()
                .map(ExecutionTask::getAgentRole)
                .filter(role -> !role.equals("CriticAgent"))
                .collect(java.util.stream.Collectors.toSet());
        plan.setSelectedAgents(new ArrayList<>(selectedAgents));

        log.info("[PlannerAgent] 动态规划成功: 风格={}, {} 个 tasks, {} 个 agents",
                detectedStyle, tasks.size(), selectedAgents.size());

        return plan;
    }

    /**
     * 解析单个任务节点
     */
    private ExecutionTask parseExecutionTask(JsonNode taskNode, Set<String> usedTaskIds) {
        try {
            ExecutionTask task = new ExecutionTask();

            // 生成或提取 taskId
            String taskId = taskNode.path("taskId").asText("");
            if (taskId.isBlank() || usedTaskIds.contains(taskId)) {
                taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
            }
            usedTaskIds.add(taskId);
            task.setTaskId(taskId);

            // Agent 角色
            String agentRole = taskNode.path("agentRole").asText("");
            if (agentRole.isBlank()) {
                log.warn("[PlannerAgent] 跳过无 agentRole 的任务");
                return null;
            }
            if ("VideoScriptAgent".equals(agentRole)) {
                agentRole = "VisualizationAgent";
            }
            task.setAgentRole(agentRole);

            // 产物类型
            String artifactType = taskNode.path("artifactType").asText(
                    mapAgentRoleToArtifactType(agentRole));
            if ("VIDEO_SCRIPT".equals(artifactType)) {
                artifactType = "VISUALIZATION";
            }
            task.setArtifactType(artifactType);

            // 优先级（1-10）
            int priority = taskNode.path("priority").asInt(5);
            task.setPriority(Math.max(1, Math.min(10, priority)));

            // 选择理由
            task.setReasoning(taskNode.path("reasoning").asText("基于学习风格选择"));

            // 依赖关系
            List<String> deps = new ArrayList<>();
            JsonNode depsNode = taskNode.path("dependencies");
            if (depsNode.isArray()) {
                depsNode.forEach(d -> {
                    String dep = d.asText("");
                    if (!dep.isBlank()) deps.add(dep);
                });
            }
            task.setDependencies(deps);

            // 工具参数
            JsonNode paramsNode = taskNode.path("toolParameters");
            if (paramsNode.isObject()) {
                Map<String, Object> params = objectMapper.convertValue(paramsNode, new TypeReference<Map<String, Object>>() {});
                task.setToolParameters(params);
            } else {
                task.setToolParameters(new HashMap<>());
            }

            return task;
        } catch (Exception e) {
            log.warn("[PlannerAgent] 解析任务节点失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 验证学习风格有效性
     */
    private boolean isValidLearningStyle(String style) {
        return Set.of("visual", "textual", "practical", "balanced").contains(style.toLowerCase());
    }

    /**
     * Agent 角色映射到产物类型
     */
    private String mapAgentRoleToArtifactType(String agentRole) {
        return switch (agentRole) {
            case "DocAgent", "LectureAgent" -> "HANDOUT";
            case "QuizAgent" -> "QUIZ";
            case "MindMapAgent" -> "MINDMAP";
            case "ReadingAgent" -> "EXTENDED_READING";
            case "CodingAgent" -> "CODE_PRACTICE";
            case "VideoScriptAgent" -> "VISUALIZATION";
            case "PathAgent" -> "LEARNING_PATH";
            case "VisualizationAgent" -> "VISUALIZATION";
            case "CriticAgent" -> "REVIEW";
            default -> "CUSTOM";
        };
    }

    /**
     * 验证并修复依赖关系
     */
    private void validateAndFixDependencies(List<ExecutionTask> tasks, Map<String, List<String>> dependencies) {
        Set<String> validTaskIds = tasks.stream()
                .map(ExecutionTask::getTaskId)
                .collect(java.util.stream.Collectors.toSet());

        for (ExecutionTask task : tasks) {
            List<String> validDeps = task.getDependencies().stream()
                    .filter(validTaskIds::contains)
                    .toList();

            if (validDeps.size() != task.getDependencies().size()) {
                List<String> invalid = task.getDependencies().stream()
                        .filter(d -> !validTaskIds.contains(d))
                        .toList();
                log.warn("[PlannerAgent] 任务 {} 有无效依赖: {}, 已移除", task.getTaskId(), invalid);
                task.setDependencies(validDeps);
                dependencies.put(task.getTaskId(), validDeps);
            }
        }

        // 确保 PathAgent 有依赖（如果存在）
        for (ExecutionTask task : tasks) {
            if ("PathAgent".equals(task.getAgentRole()) && task.getDependencies().isEmpty()) {
                // 自动添加所有非 PathAgent、非 CriticAgent 的任务为依赖
                List<String> autoDeps = tasks.stream()
                        .filter(t -> !"PathAgent".equals(t.getAgentRole()) && !"CriticAgent".equals(t.getAgentRole()))
                        .map(ExecutionTask::getTaskId)
                        .toList();
                task.setDependencies(autoDeps);
                dependencies.put(task.getTaskId(), autoDeps);
                log.info("[PlannerAgent] 自动为 PathAgent 添加 {} 个依赖", autoDeps.size());
            }
        }
    }

    /**
     * 确保 CriticAgent 存在（质量控制）
     */
    private void ensureCriticAgentExists(List<ExecutionTask> tasks, Map<String, List<String>> dependencies, Set<String> usedTaskIds) {
        boolean hasCritic = tasks.stream().anyMatch(t -> "CriticAgent".equals(t.getAgentRole()));

        if (!hasCritic) {
            // 添加 CriticAgent 作为最后一个任务
            ExecutionTask criticTask = new ExecutionTask();
            String criticId = "task-critic-" + UUID.randomUUID().toString().substring(0, 8);
            criticTask.setTaskId(criticId);
            criticTask.setAgentRole("CriticAgent");
            criticTask.setArtifactType("REVIEW");
            criticTask.setPriority(10);
            criticTask.setReasoning("质量审查：对所有生成的资源进行统一评审");

            // 依赖所有其他任务
            List<String> allDeps = tasks.stream()
                    .map(ExecutionTask::getTaskId)
                    .toList();
            criticTask.setDependencies(allDeps);

            tasks.add(criticTask);
            dependencies.put(criticId, allDeps);

            log.info("[PlannerAgent] 自动添加 CriticAgent 进行质量控制");
        }
    }

    /**
     * 构建动态规划系统提示词 - 混合架构专用
     *
     * <p>此提示词负责：
     * 1. 解析 LearnerProfile 检测学习风格
     * 2. 根据学习风格动态选择 Specialist Agents
     * 3. 输出 JSON 格式的任务执行清单（包含工具依赖关系）
     */
    private String buildDynamicPlanningSystemPrompt() {
        return """
            你是 VisionaryTutor 的 PlannerAgent（混合架构智能规划器）。

            ## 核心任务
            分析学生画像，识别学习风格，动态规划最适合该学生的学习资源生成任务。

            ## 学习风格定义与检测特征

            ### visual (视觉型学习者)
            - 检测关键词："喜欢图表"、"看图理解"、"视觉记忆"、"动画"、"视频"、"流程图"
            - 行为特征：偏好图像、颜色、空间关系
            - 适用场景：图像识别、卷积可视化、空间概念、几何图形

            ### textual (文字型学习者)
            - 检测关键词："阅读"、"笔记"、"文字解释"、"文档"、"书本"、"定义"
            - 行为特征：偏好阅读文字、记笔记、书面解释
            - 适用场景：理论学习、概念理解、定义记忆、公式推导

            ### practical (实践型学习者)
            - 检测关键词："动手"、"实践"、"代码"、"实验"、"项目"、"练习"
            - 行为特征：偏好动手操作、编码实践、实际应用
            - 适用场景：算法实现、项目实战、编程练习、实验操作

            ### balanced (平衡型学习者)
            - 检测关键词：综合上述特征不明显，或明确提到"综合"、"平衡"
            - 适用场景：需要全面覆盖各类资源

            ## Agent 与工具映射表

            | Agent角色 | 对应工具 | 适用学习风格 | 产物类型 |
            |-----------|----------|--------------|----------|
            | MindMapAgent | generate_mind_map | visual/textual | MINDMAP |
            | VisualizationAgent | generate_visualization | visual | VISUALIZATION（本地演示动画＋文字注解） |
            | DocAgent | generate_lecture_handout | textual | HANDOUT |
            | ReadingAgent | generate_reading_materials | textual | EXTENDED_READING |
            | QuizAgent | generate_quiz | textual/practical | QUIZ |
            | CodingAgent | generate_coding_practice | practical | CODE_PRACTICE |
            | PathAgent | generate_learning_path | all | LEARNING_PATH |
            | CriticAgent | review_and_critique | all | REVIEW |

            ## 动态选择策略

            ### visual 风格优先选择（优先级 8-10）：
            1. VisualizationAgent (priority=10) - 本地演示动画与同步文字注解
            2. MindMapAgent (priority=8) - 思维导图
            3. QuizAgent (priority=6) - 测验（巩固）
            4. PathAgent (priority=5, 依赖其他) - 学习路径

            ### textual 风格优先选择（优先级 8-10）：
            1. DocAgent (priority=10) - 讲义
            2. ReadingAgent (priority=9) - 拓展阅读
            3. QuizAgent (priority=8) - 测验
            4. MindMapAgent (priority=6) - 思维导图
            5. PathAgent (priority=5, 依赖其他) - 学习路径

            ### practical 风格优先选择（优先级 8-10）：
            1. CodingAgent (priority=10) - 代码实操
            2. QuizAgent (priority=8) - 测验（实践检验）
            3. PathAgent (priority=7, 依赖其他) - 学习路径
            4. DocAgent (priority=6) - 讲义（参考）
            5. MindMapAgent (priority=5) - 思维导图

            ### balanced 风格选择：
            - 均匀分配：DocAgent、QuizAgent、MindMapAgent、CodingAgent、VisualizationAgent
            - 各 Agent priority=7

            ## 依赖关系规则（严格遵循）
            1. PathAgent 必须依赖至少 2 个内容生成 Agent
            2. CriticAgent 总是在所有内容生成 Agent 之后执行
            3. 无依赖的 Agent 可以并行执行
            4. 依赖关系用 taskId 列表表示

            ## 输出格式（严格 JSON，无 Markdown 标记）

            {
              "learningStyle": "visual|textual|practical|balanced",
              "planSummary": "一句话总结，例如：针对视觉型学习者，优先生成本地动画和导图资源",
              "tasks": [
                {
                  "taskId": "task-1",
                  "agentRole": "VisualizationAgent",
                  "artifactType": "VISUALIZATION",
                  "priority": 10,
                  "dependencies": [],
                  "reasoning": "视觉型学习者偏好视频学习，通过动态图解理解概念",
                  "toolParameters": {
                    "depth": "MEDIUM",
                    "vizType": "ANIMATED_EXPLAINER",
                    "interactivity": "ANIMATED"
                  }
                },
                {
                  "taskId": "task-5",
                  "agentRole": "PathAgent",
                  "artifactType": "LEARNING_PATH",
                  "priority": 5,
                  "dependencies": ["task-1", "task-2", "task-3"],
                  "reasoning": "整合前面生成的资源，规划学习路径",
                  "toolParameters": {
                    "targetDuration": "2周"
                  }
                }
              ]
            }

            ## 约束条件
            1. 必须检测并输出 learningStyle
            2. 至少选择 3 个、最多选择 7 个 Agent（不含 Critic）
            3. 优先级范围 1-10，风格相关 Agent 必须 ≥8
            4. 每个 Agent 必须有 reasoning 说明选择依据
            5. 只输出 JSON，不要任何 Markdown 代码块标记
            6. 必须根据用户当前问题调整策略（如果是编程问题，即使非实践型也要包含 CodingAgent）
            """;
    }

    /**
     * 构建动态规划用户提示词 - 包含画像分析指导和当前问题上下文
     */
    private String buildDynamicPlanningUserPrompt(String topic, String profile, String weakPoints, String userQuestion) {
        String effectiveQuestion = (userQuestion == null || userQuestion.isBlank()) ? "生成完整学习资源包" : userQuestion;

        // 检测问题类型以辅助决策
        String questionTypeHint = detectQuestionTypeHint(effectiveQuestion);

        return """
            学习目标：%s

            学生画像（关键：识别学习风格特征）：
            %s

            薄弱点（用于确定重点 Agent）：
            %s

            当前问题/需求（影响 Agent 选择）：
            %s

            %s

            ## 分析要求
            1. 仔细阅读学生画像，识别偏好关键词（视觉词汇/文字词汇/实践词汇）
            2. 根据 learningStyle 选择对应的高优先级 Agents
            3. 结合薄弱点，确保覆盖相关知识点
            4. 考虑当前问题类型，动态调整
            5. 为每个选中的 Agent 撰写具体的 reasoning

            ## 输出检查清单
            - [ ] learningStyle 已正确检测并输出
            - [ ] tasks 数组非空且每个 task 包含 taskId/agentRole/artifactType/priority/dependencies/reasoning
            - [ ] 依赖关系符合规则（PathAgent 依赖其他 Agent）
            - [ ] 优先级设置反映学习风格偏好
            - [ ] 输出是纯 JSON，无 ```json 或 ``` 标记

            请输出 JSON：
            """.formatted(
                topic,
                truncate(profile, orchestrationProps.getProfileTruncateLength()),
                truncate(weakPoints, orchestrationProps.getWeakPointsTruncateLength()),
                effectiveQuestion,
                questionTypeHint
        );
    }

    /**
     * 检测问题类型并返回提示
     */
    private String detectQuestionTypeHint(String question) {
        String lower = question.toLowerCase();
        if (lower.contains("代码") || lower.contains("编程") || lower.contains("实现") ||
            lower.contains("python") || lower.contains("java") || lower.contains("算法")) {
            return "【问题类型：编程/代码实现】→ 即使非实践型学习者，也强烈建议包含 CodingAgent";
        }
        if (lower.contains("可视化") || lower.contains("图像") || lower.contains("视频") ||
            lower.contains("动画") || lower.contains("图表")) {
            return "【问题类型：可视化/图形】→ 建议优先 VisualizationAgent 生成本地动画，并用 MindMapAgent 补充结构图";
        }
        if (lower.contains("理论") || lower.contains("概念") || lower.contains("定义") ||
            lower.contains("解释") || lower.contains("什么是")) {
            return "【问题类型：理论学习】→ 建议优先 DocAgent 和 ReadingAgent";
        }
        if (lower.contains("路径") || lower.contains("计划") || lower.contains("学习路线") ||
            lower.contains("步骤")) {
            return "【问题类型：路径规划】→ 必须包含 PathAgent，且依赖其他 Agents";
        }
        return "";
    }

    /**
     * 解析 JSON 响应 - 多级容错解析
     *
     * <p>尝试顺序：
     * 1. 直接解析完整响应
     * 2. 从 Markdown 代码块提取
     * 3. 从文本中提取 JSON 对象
     * 4. 清理常见格式问题后重试
     */
    private JsonNode parseJsonResponse(String response) {
        if (response == null || response.isBlank()) {
            log.error("[PlannerAgent] 响应为空");
            return null;
        }

        // 尝试1：直接解析
        try {
            return objectMapper.readTree(response.trim());
        } catch (Exception e) {
            log.debug("[PlannerAgent] 直接解析失败，尝试提取: {}", e.getMessage());
        }

        // 尝试2：从 Markdown 代码块提取
        try {
            String json = extractJsonFromMarkdown(response);
            if (!json.equals(response)) {
                return objectMapper.readTree(json.trim());
            }
        } catch (Exception e) {
            log.debug("[PlannerAgent] Markdown 提取解析失败: {}", e.getMessage());
        }

        // 尝试3：清理常见格式问题
        try {
            String cleaned = cleanJsonResponse(response);
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.error("[PlannerAgent] 所有解析尝试失败，原始响应前200字符: {}",
                    String.valueOf(response).substring(0, Math.min(200, response.length())));
            return null;
        }
    }

    /**
     * 从 Markdown 代码块中提取 JSON - 增强版
     */
    private String extractJsonFromMarkdown(String text) {
        if (text == null) return "{}";

        String trimmed = text.trim();

        // 寻找 ```json ... ``` 块
        int start = trimmed.indexOf("```json");
        if (start >= 0) {
            int contentStart = start + 7; // length of "```json"
            int end = trimmed.indexOf("```", contentStart);
            if (end > contentStart) {
                return trimmed.substring(contentStart, end).trim();
            }
        }

        // 寻找 ``` ... ``` 块
        start = trimmed.indexOf("```");
        if (start >= 0) {
            int contentStart = start + 3;
            int end = trimmed.indexOf("```", contentStart);
            if (end > contentStart) {
                String content = trimmed.substring(contentStart, end).trim();
                // 检查是否以 { 开头（JSON对象）
                if (content.startsWith("{") && content.endsWith("}")) {
                    return content;
                }
            }
        }

        // 寻找第一个 { 和最后一个 }（嵌套对象支持）
        int braceStart = trimmed.indexOf('{');
        int braceEnd = trimmed.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return trimmed.substring(braceStart, braceEnd + 1);
        }

        return text;
    }

    /**
     * 清理 JSON 响应中的常见问题
     */
    private String cleanJsonResponse(String text) {
        String cleaned = text.trim();

        // 移除 BOM 标记
        if (cleaned.startsWith("\uFEFF")) {
            cleaned = cleaned.substring(1);
        }

        // 移除常见的 Markdown 前缀/后缀
        cleaned = cleaned.replaceAll("^```\\w*\\s*", "");
        cleaned = cleaned.replaceAll("```\\s*$", "");

        // 移除控制字符
        cleaned = cleaned.replaceAll("[\\x00-\\x1F\\x7F]", "");

        // 修复大括号内侧前后的多余空格（去除换行和缩进，有助于平铺 JSON）
        cleaned = cleaned.replaceAll("(?<=\\{)\\s+", "");
        cleaned = cleaned.replaceAll("\\s+(?=\\})", "");

        return cleaned.trim();
    }

    /**
     * 构建默认的全量回退规划
     */
    private DynamicExecutionPlan buildFallbackPlan(String topic) {
        DynamicExecutionPlan plan = new DynamicExecutionPlan();
        plan.setTopic(topic);
        plan.setDetectedLearningStyle("balanced");
        plan.setPlanSummary("全量并行生成（回退模式）");
        plan.setFallback(true);

        List<ExecutionTask> tasks = new ArrayList<>();
        Map<String, List<String>> dependencies = new HashMap<>();

        // 并行阶段（无依赖）
        String[] parallelAgents = {"DocAgent", "QuizAgent", "MindMapAgent", "ReadingAgent", "CodingAgent", "VisualizationAgent"};
        String[] parallelTypes = {"HANDOUT", "QUIZ", "MINDMAP", "EXTENDED_READING", "CODE_PRACTICE", "VISUALIZATION"};

        for (int i = 0; i < parallelAgents.length; i++) {
            ExecutionTask task = new ExecutionTask();
            task.setTaskId("task-" + i);
            task.setAgentRole(parallelAgents[i]);
            task.setArtifactType(parallelTypes[i]);
            task.setPriority(5);
            task.setReasoning("回退模式：全量并行生成");
            task.setDependencies(List.of());
            task.setToolParameters(Map.of("depth", "MEDIUM"));
            tasks.add(task);
            dependencies.put(task.getTaskId(), List.of());
        }

        // PathAgent 依赖前面所有
        ExecutionTask pathTask = new ExecutionTask();
        pathTask.setTaskId("task-path");
        pathTask.setAgentRole("PathAgent");
        pathTask.setArtifactType("LEARNING_PATH");
        pathTask.setPriority(7);
        pathTask.setReasoning("整合所有资源生成学习路径");
        List<String> pathDeps = new ArrayList<>();
        for (int i = 0; i < parallelAgents.length; i++) {
            pathDeps.add("task-" + i);
        }
        pathTask.setDependencies(pathDeps);
        pathTask.setToolParameters(Map.of("depth", "MEDIUM"));
        tasks.add(pathTask);
        dependencies.put(pathTask.getTaskId(), pathDeps);

        plan.setTasks(tasks);
        plan.setTaskDependencies(dependencies);
        plan.setSelectedAgents(Arrays.asList(parallelAgents));

        return plan;
    }

    private String truncate(String text, int max) {
        if (text == null || text.isBlank()) {
            return "暂无";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    // ==================== 动态执行计划数据类 ====================

    public static class DynamicExecutionPlan {
        private String topic;
        private String detectedLearningStyle = "balanced";
        private String planSummary;
        private List<ExecutionTask> tasks = new ArrayList<>();
        private Map<String, List<String>> taskDependencies = new HashMap<>();
        private List<String> selectedAgents = new ArrayList<>();
        private boolean fallback = false;

        public boolean isValid() {
            return tasks != null && !tasks.isEmpty() && detectedLearningStyle != null;
        }

        // Getters and Setters
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getDetectedLearningStyle() { return detectedLearningStyle; }
        public void setDetectedLearningStyle(String detectedLearningStyle) { this.detectedLearningStyle = detectedLearningStyle; }
        public String getPlanSummary() { return planSummary; }
        public void setPlanSummary(String planSummary) { this.planSummary = planSummary; }
        public List<ExecutionTask> getTasks() { return tasks; }
        public void setTasks(List<ExecutionTask> tasks) { this.tasks = tasks; }
        public Map<String, List<String>> getTaskDependencies() { return taskDependencies; }
        public void setTaskDependencies(Map<String, List<String>> taskDependencies) { this.taskDependencies = taskDependencies; }
        public List<String> getSelectedAgents() { return selectedAgents; }
        public void setSelectedAgents(List<String> selectedAgents) { this.selectedAgents = selectedAgents; }
        public boolean isFallback() { return fallback; }
        public void setFallback(boolean fallback) { this.fallback = fallback; }
    }

    public static class ExecutionTask {
        private String taskId;
        private String agentRole;
        private String artifactType;
        private int priority;
        private List<String> dependencies = new ArrayList<>();
        private String reasoning;
        private Map<String, Object> toolParameters = new HashMap<>();

        // Getters and Setters
        public String getTaskId() { return taskId; }
        public void setTaskId(String taskId) { this.taskId = taskId; }
        public String getAgentRole() { return agentRole; }
        public void setAgentRole(String agentRole) { this.agentRole = agentRole; }
        public String getArtifactType() { return artifactType; }
        public void setArtifactType(String artifactType) { this.artifactType = artifactType; }
        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
        public List<String> getDependencies() { return dependencies; }
        public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }
        public String getReasoning() { return reasoning; }
        public void setReasoning(String reasoning) { this.reasoning = reasoning; }
        public Map<String, Object> getToolParameters() { return toolParameters; }
        public void setToolParameters(Map<String, Object> toolParameters) { this.toolParameters = toolParameters; }
    }
}
