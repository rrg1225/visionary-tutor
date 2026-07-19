package com.visionary.agent.learning;

import com.visionary.dto.StreamChatRequest;
import com.visionary.rag.KnowledgeLayer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic supervisor for interactive tutoring.
 *
 * <p>The resource generator already has an autonomous ReAct loop. Interactive chat needs a
 * lower-latency workflow, so this supervisor uses typed, observable routing and only invokes the
 * tools required by the current learner goal. Later multimodal/question-bank entry points can
 * reuse the same plan contract.</p>
 */
@Service
public class LearningSupervisorService {

    public LearningAgentPlan plan(StreamChatRequest request, String query) {
        String normalized = normalize(query);
        LearningAgentPlan.Intent intent = resolveIntent(normalized);
        boolean requiresGrounding = requiresGrounding(intent, normalized);
        List<KnowledgeLayer> layers = requiresGrounding ? layersFor(intent, normalized) : List.of();

        List<String> tools = new ArrayList<>();
        tools.add("learner_memory.read");
        if (requiresGrounding) {
            tools.add("knowledge.retrieve");
            tools.add("answer.grounding_check");
        }
        tools.add("learner_memory.update");

        List<LearningAgentPlan.VisibleStep> steps = new ArrayList<>();
        steps.add(new LearningAgentPlan.VisibleStep("understand", "理解问题与学习目标", "COMPLETED"));
        if (requiresGrounding) {
            steps.add(new LearningAgentPlan.VisibleStep("retrieve", "检索可信学习资料", "RUNNING"));
        }
        steps.add(new LearningAgentPlan.VisibleStep("teach", responseStepLabel(intent), "PENDING"));
        steps.add(new LearningAgentPlan.VisibleStep("update", "更新本次学习记忆", "PENDING"));

        return new LearningAgentPlan(
                intent,
                learnerGoal(request, query),
                List.copyOf(layers),
                List.copyOf(tools),
                List.copyOf(steps),
                requiresGrounding,
                responseStrategy(intent)
        );
    }

    public String systemInstruction(LearningAgentPlan plan) {
        return """
                [Learning Supervisor Plan]
                当前教学意图：%s
                学习目标：%s
                回答策略：%s
                仅执行已选择的工具，不要虚构未执行的测评、检索、代码运行或画像更新。
                """.formatted(plan.intent().name(), plan.learnerGoal(), plan.responseStrategy());
    }

    private LearningAgentPlan.Intent resolveIntent(String query) {
        if (containsAny(query, "怎么用", "使用指南", "功能在哪", "平台功能", "如何上传", "如何注册")) {
            return LearningAgentPlan.Intent.PLATFORM_GUIDANCE;
        }
        if (containsAny(query, "代码", "报错", "debug", "python", "java", "c++", "pytorch", "运行结果", "复杂度")) {
            return LearningAgentPlan.Intent.CODE_TUTORING;
        }
        if (containsAny(query, "这道题", "题目", "作业", "答案", "错题", "测评", "为什么错")) {
            return LearningAgentPlan.Intent.ASSESSMENT_SUPPORT;
        }
        if (containsAny(query, "教材", "阅读", "论文", "章节", "这段话", "文献")) {
            return LearningAgentPlan.Intent.READING_GUIDANCE;
        }
        if (containsAny(query, "学习计划", "学习路径", "怎么学", "入门路线", "复习计划", "资源推荐")) {
            return LearningAgentPlan.Intent.LEARNING_PLANNING;
        }
        return LearningAgentPlan.Intent.TUTORING;
    }

    private boolean requiresGrounding(LearningAgentPlan.Intent intent, String query) {
        if (intent == LearningAgentPlan.Intent.PLATFORM_GUIDANCE) {
            return false;
        }
        return !containsAny(query, "你好", "谢谢", "在吗", "继续", "明白了", "好的");
    }

    private List<KnowledgeLayer> layersFor(LearningAgentPlan.Intent intent, String query) {
        if (intent == LearningAgentPlan.Intent.CODE_TUTORING) {
            return List.of(KnowledgeLayer.CODE, KnowledgeLayer.ALGORITHM, KnowledgeLayer.COURSE, KnowledgeLayer.CONCEPT);
        }
        if (intent == LearningAgentPlan.Intent.ASSESSMENT_SUPPORT) {
            return List.of(KnowledgeLayer.EXERCISE, KnowledgeLayer.ASSESSMENT, KnowledgeLayer.CONCEPT, KnowledgeLayer.MATH);
        }
        if (intent == LearningAgentPlan.Intent.READING_GUIDANCE) {
            return List.of(KnowledgeLayer.COURSE, KnowledgeLayer.CONCEPT, KnowledgeLayer.UGC);
        }
        if (intent == LearningAgentPlan.Intent.LEARNING_PLANNING) {
            return List.of(KnowledgeLayer.COURSE, KnowledgeLayer.CONCEPT, KnowledgeLayer.EXERCISE, KnowledgeLayer.UGC);
        }
        if (containsAny(query, "公式", "推导", "证明", "计算", "概率", "矩阵", "梯度", "微积分", "线性代数")) {
            return List.of(KnowledgeLayer.MATH, KnowledgeLayer.COURSE, KnowledgeLayer.CONCEPT, KnowledgeLayer.EXERCISE);
        }
        return List.of(KnowledgeLayer.COURSE, KnowledgeLayer.CONCEPT, KnowledgeLayer.EXERCISE, KnowledgeLayer.UGC);
    }

    private String responseStepLabel(LearningAgentPlan.Intent intent) {
        return switch (intent) {
            case CODE_TUTORING -> "定位代码问题并给出可验证步骤";
            case ASSESSMENT_SUPPORT -> "分析作答过程并解释错误原因";
            case READING_GUIDANCE -> "结合当前材料分层讲解";
            case LEARNING_PLANNING -> "生成可执行的学习计划";
            case PLATFORM_GUIDANCE -> "给出明确的平台操作步骤";
            default -> "组织分步骤教学回答";
        };
    }

    private String responseStrategy(LearningAgentPlan.Intent intent) {
        return switch (intent) {
            case CODE_TUTORING -> "先定位根因，再给最小修复和验证方法；未实际运行代码时必须明确说明";
            case ASSESSMENT_SUPPORT -> "先判断是否允许揭示答案；未提交时优先提示或分步，提交后再解释关键步骤、错误原因和巩固题";
            case READING_GUIDANCE -> "先解释选中内容，再补先修概念、例子和来源";
            case LEARNING_PLANNING -> "给出有顺序、预计投入和完成标准的行动计划";
            case PLATFORM_GUIDANCE -> "使用短步骤说明入口与操作，不调用知识库";
            default -> "根据本次辅导模式直接解释、给提示或逐步引导，再给例子、误区和自检";
        };
    }

    private String learnerGoal(StreamChatRequest request, String query) {
        String explicit = query == null ? "" : query.trim();
        if (!explicit.isBlank()) {
            return truncate(explicit, 160);
        }
        return truncate(request.studentProfileSnapshot(), 160);
    }

    private static String normalize(String query) {
        return query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            return "解决当前学习问题";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }
}
