package com.visionary.governance;

import com.visionary.config.GovernanceProperties;
import com.visionary.entity.GeneratedArtifact;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 复合评分计算器 — 纯数学聚合，不依赖 LLM / HTTP 等外部 API。
 * <p>
 * 将「可自动校验的客观规则分」与「Critic LLM 主观分」加权融合，
 * 为 {@link GovernanceCircuitBreaker} 提供统一的 {@code currentScore} / {@code previousScore} 输入。
 * </p>
 *
 * <h3>复合分公式</h3>
 * <pre>
 *   compositeScore = w<sub>obj</sub> × objectiveRuleScore + w<sub>llm</sub> × llmScore
 *                  = 0.4 × 客观规则分 + 0.6 × LLM 主观分   （默认权重，可通过 governance.* 配置覆盖）
 * </pre>
 *
 * <h3>Δ 增益的业务含义</h3>
 * <p>
 * {@code Δ = currentScore − previousScore} 衡量<strong>相邻两轮返修</strong>带来的质量净提升：
 * </p>
 * <ul>
 *   <li><strong>Δ &gt; 0</strong> — 返修有效，产物质量正向演进；可继续投入下一轮 Critic 审查。</li>
 *   <li><strong>Δ = 0</strong> — 返修停滞，模型未实质改进内容；继续循环浪费算力且可能陷入振荡。</li>
 *   <li><strong>Δ &lt; 0</strong> — 负优化，返修后质量反而下降；必须熔断并保留上一轮较优版本。</li>
 *   <li><strong>0 &lt; Δ &lt; 阈值</strong> — 边际效用过低：虽有微弱提升，但不足以 justify 额外 LLM 调用成本。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class CompositeScoreCalculator {

    private static final double SCORE_MIN = 0.0D;
    private static final double SCORE_MAX = 100.0D;

    private final GovernanceProperties governanceProperties;

    /**
     * 根据产物元数据与 LLM 主观分计算最终复合得分。
     *
     * @param artifact  已生成的学习资源产物（仅读取 validation / content 等字段，不触发 IO）
     * @param llmScore  Critic LLM 主观评分，量纲 0–100（若传入 0–1 小数，请先 ×100）
     * @return 复合得分，范围 [0, 100]
     */
    public double computeCompositeScore(GeneratedArtifact artifact, double llmScore) {
        double objective = computeObjectiveRuleScore(artifact);
        double boundedLlm = clamp(llmScore);
        double objectiveWeight = governanceProperties.getObjectiveWeight();
        double llmWeight = governanceProperties.getLlmWeight();
        return clamp(objectiveWeight * objective + llmWeight * boundedLlm);
    }

    /**
     * 计算相邻两轮之间的得分增益 Δ。
     *
     * @param currentScore  本轮复合得分
     * @param previousScore 上一轮复合得分
     * @return Δ = current − previous；正数表示质量提升
     */
    public double computeScoreDelta(double currentScore, double previousScore) {
        return currentScore - previousScore;
    }

    /**
     * 客观规则分（0–100）：仅依据产物可观测字段，模拟 CitationValidator / 完整性检查等硬性规则。
     * <p>权重分配：证据校验 40 + 内容完整 30 + 引用载荷 15 + 生成进度 15。</p>
     */
    public double computeObjectiveRuleScore(GeneratedArtifact artifact) {
        if (artifact == null) {
            return SCORE_MIN;
        }

        double score = 0.0D;
        score += validationStatusPoints(artifact.getValidationStatus());
        score += contentCompletenessPoints(artifact.getContentMarkdown());
        score += citationPayloadPoints(artifact.getCitationsJson());
        score += progressPoints(artifact.getProgress());

        return clamp(score);
    }

    private double validationStatusPoints(String validationStatus) {
        if (!StringUtils.hasText(validationStatus)) {
            return 5.0D;
        }
        return switch (validationStatus.trim().toUpperCase()) {
            case "GROUNDED" -> 40.0D;
            case "VERIFIED" -> 35.0D;
            case "UNVERIFIED" -> 15.0D;
            case "NO_EVIDENCE", "RAG_UNUSED" -> 35.0D;
            case "INVALID_CITATION", "NO_CITATION_USED" -> 0.0D;
            default -> 5.0D;
        };
    }

    private double contentCompletenessPoints(String contentMarkdown) {
        if (!StringUtils.hasText(contentMarkdown)) {
            return 0.0D;
        }
        int length = contentMarkdown.trim().length();
        if (length >= 500) {
            return 30.0D;
        }
        if (length >= 100) {
            return 20.0D;
        }
        return 10.0D;
    }

    private double citationPayloadPoints(String citationsJson) {
        if (!StringUtils.hasText(citationsJson)) {
            return 0.0D;
        }
        String trimmed = citationsJson.trim();
        if ("[]".equals(trimmed) || "{}".equals(trimmed)) {
            return 5.0D;
        }
        return 15.0D;
    }

    private double progressPoints(Integer progress) {
        if (progress == null) {
            return 0.0D;
        }
        if (progress >= 100) {
            return 15.0D;
        }
        if (progress >= 50) {
            return 8.0D;
        }
        return 0.0D;
    }

    private double clamp(double value) {
        return Math.max(SCORE_MIN, Math.min(SCORE_MAX, value));
    }
}
