package com.visionary.os;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PolicyEngine {

    public PolicyDecision decide(LearningEvent event) {
        return switch (event.type()) {
            case QUIZ_SUBMITTED -> decideAfterQuiz(event);
            case PROFILE_UPDATED -> decideAfterProfile(event);
            case ASSESSMENT_COMPLETED -> decideAfterAssessment(event);
            default -> PolicyDecision.noop("事件已记录，无需策略动作");
        };
    }

    private PolicyDecision decideAfterQuiz(LearningEvent event) {
        Map<String, Object> payload = event.payload();
        double accuracy = toDouble(payload.get("accuracy"), 1.0D);
        @SuppressWarnings("unchecked")
        List<String> weakPoints = payload.get("newWeakPoints") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();

        if (accuracy >= 0.6 && weakPoints.isEmpty()) {
            return PolicyDecision.noop("测验表现稳定，暂不触发重规划");
        }

        List<PolicyAction> actions = new ArrayList<>();
        actions.add(PolicyAction.EXTRACT_PROFILE_FROM_QUIZ);
        actions.add(PolicyAction.REPLAN_PATH);
        actions.add(PolicyAction.GENERATE_REMEDIAL_AGENTS);
        actions.add(PolicyAction.PUSH_RECOMMENDATIONS);

        String reason = weakPoints.isEmpty()
                ? "测验准确率 %.0f%% 低于阈值，触发专项补救资源与路径重规划".formatted(accuracy * 100)
                : "检测到薄弱点「%s」，触发 Agent 协同生成补救资源".formatted(String.join("、", weakPoints.stream().limit(3).toList()));

        return new PolicyDecision(true, reason, actions);
    }

    private PolicyDecision decideAfterProfile(LearningEvent event) {
        String phase = String.valueOf(event.payload().getOrDefault("extractPhase", "FULL"));
        if ("USER_TURN".equalsIgnoreCase(phase)) {
            return PolicyDecision.noop("用户发言已更新画像，暂不整包重生成");
        }
        return new PolicyDecision(
                false,
                "画像版本 v" + event.profileVersion() + " 已同步至学习 OS",
                List.of(PolicyAction.SYNC_STATE_ONLY)
        );
    }

    private PolicyDecision decideAfterAssessment(LearningEvent event) {
        List<String> weakPoints = event.payload().get("weakPoints") instanceof List<?> list
                ? list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList()
                : List.of();
        if (weakPoints.isEmpty()) {
            return new PolicyDecision(
                    true,
                    "作业评测完成，已更新路径",
                    List.of(PolicyEngine.PolicyAction.REPLAN_PATH, PolicyEngine.PolicyAction.PUSH_RECOMMENDATIONS)
            );
        }
        return new PolicyDecision(
                true,
                "作业评测发现薄弱点「" + String.join("、", weakPoints.stream().limit(3).toList()) + "」，触发 Agent 专项资源",
                List.of(
                        PolicyEngine.PolicyAction.REPLAN_PATH,
                        PolicyEngine.PolicyAction.GENERATE_REMEDIAL_AGENTS,
                        PolicyEngine.PolicyAction.PUSH_RECOMMENDATIONS
                )
        );
    }

    private static double toDouble(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    public enum PolicyAction {
        SYNC_STATE_ONLY,
        EXTRACT_PROFILE_FROM_QUIZ,
        REPLAN_PATH,
        GENERATE_REMEDIAL_AGENTS,
        PUSH_RECOMMENDATIONS
    }

    public record PolicyDecision(boolean executeActions, String reason, List<PolicyAction> actions) {
        public static PolicyDecision noop(String reason) {
            return new PolicyDecision(false, reason, List.of());
        }
    }
}
