package com.visionary.governance;

import com.visionary.config.GovernanceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 审计治理层断路器 — 纯状态机，不调用外部 API。
 * <p>
 * 在 Critic 多轮返修循环中，根据当前轮次与得分变化（Δ）判定是否继续编排。
 * </p>
 *
 * <h3>判定优先级（由高到低）</h3>
 * <ol>
 *   <li>{@code HALT_MAX_ROUND} — 轮次达到配置上限（默认 5）</li>
 *   <li>{@code HALT_NEGATIVE_DELTA} — Δ ≤ 0，返修未带来正向提升</li>
 *   <li>{@code HALT_CONVERGENCE} — 连续两轮 Δ 均低于收敛阈值（默认 0.05，按百分制归一化）</li>
 *   <li>{@code HALT_LOW_MARGINAL_UTILITY} — 0 &lt; Δ &lt; 边际阈值，投入产出比过低</li>
 *   <li>{@code CONTINUE} — 允许进入下一轮返修</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class GovernanceCircuitBreaker {

    public static final String CODE_CONTINUE = "CONTINUE";
    public static final String CODE_HALT_MAX_ROUND = "HALT_MAX_ROUND";
    public static final String CODE_HALT_NEGATIVE_DELTA = "HALT_NEGATIVE_DELTA";
    public static final String CODE_HALT_CONVERGENCE = "HALT_CONVERGENCE";
    public static final String CODE_HALT_LOW_MARGINAL_UTILITY = "HALT_LOW_MARGINAL_UTILITY";

    private final GovernanceProperties governanceProperties;

    public BreakerDecision evaluate(int currentRound, double currentScore, double previousScore) {
        return evaluate(currentRound, currentScore, previousScore, null);
    }

    /**
     * @param priorRoundDelta 上一轮 Δ（S<sub>r-1</sub> − S<sub>r-2</sub>）；第 2 轮起用于收敛探测
     */
    public BreakerDecision evaluate(
            int currentRound,
            double currentScore,
            double previousScore,
            Double priorRoundDelta
    ) {
        int maxRounds = governanceProperties.getMaxRevisionRounds();
        if (currentRound >= maxRounds) {
            return BreakerDecision.halt(
                    CODE_HALT_MAX_ROUND,
                    "已达最大返修轮次上限 (%d/%d)，强制熔断并返回当前最优解".formatted(currentRound, maxRounds)
            );
        }

        if (currentRound > 0) {
            double delta = currentScore - previousScore;

            if (delta <= 0.0D) {
                return BreakerDecision.halt(
                        CODE_HALT_NEGATIVE_DELTA,
                        "得分增益 Δ=%.2f ≤ 0，返修产生负优化或停滞，停止循环并保留最优解".formatted(delta)
                );
            }

            if (currentRound >= 2 && priorRoundDelta != null) {
                double recentDelta = delta;
                if (isConverged(recentDelta, priorRoundDelta)) {
                    return BreakerDecision.halt(
                            CODE_HALT_CONVERGENCE,
                            "连续两轮得分提升均低于收敛阈值 %.3f（Δ_recent=%.2f, Δ_prior=%.2f），提前收敛熔断"
                                    .formatted(governanceProperties.getConvergenceDeltaThreshold(), recentDelta, priorRoundDelta)
                    );
                }
            }

            double marginalThreshold = governanceProperties.getDeltaThreshold();
            if (delta < marginalThreshold) {
                return BreakerDecision.halt(
                        CODE_HALT_LOW_MARGINAL_UTILITY,
                        "得分增益 Δ=%.2f 低于边际阈值 %.2f，继续返修边际效用不足".formatted(delta, marginalThreshold)
                );
            }
        }

        return BreakerDecision.continueLoop(
                "轮次 %d/%d，得分增益满足继续条件".formatted(currentRound, maxRounds)
        );
    }

    private boolean isConverged(double recentDelta, double priorRoundDelta) {
        double threshold = governanceProperties.getConvergenceDeltaThreshold();
        return normalizeDelta(recentDelta) < threshold && normalizeDelta(priorRoundDelta) < threshold;
    }

    /** 将百分制 Δ 映射到 [0,1] 区间，使 0.05 阈值等价于 5 分提升。 */
    static double normalizeDelta(double delta) {
        return Math.abs(delta) / 100.0D;
    }

    public record BreakerDecision(boolean shouldContinue, String decisionCode, String reason) {

        public static BreakerDecision continueLoop(String reason) {
            return new BreakerDecision(true, CODE_CONTINUE, reason);
        }

        public static BreakerDecision halt(String decisionCode, String reason) {
            return new BreakerDecision(false, decisionCode, reason);
        }
    }
}
