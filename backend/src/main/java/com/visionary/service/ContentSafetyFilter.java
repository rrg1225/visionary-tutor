package com.visionary.service;

import com.visionary.client.DeepSeekApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/**
 * ContentSafetyFilter - upgraded academic & exam integrity filter.
 * Double-check layer after CriticAgent.
 * - Prohibits direct exam answers (regex + optional LLM Judge)
 * - Blocks low-factuality or un-cited content (factualityScore < 0.7)
 * - Expanded academic sensitive terms (代写, 泄题, 考试作弊, etc.)
 * - Returns structured result for auto-revise/reject.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentSafetyFilter {

    private final DeepSeekApiClient deepSeekApiClient;

    // Expanded academic integrity sensitive patterns
    private static final List<Pattern> ACADEMIC_SENSITIVE = List.of(
            Pattern.compile("(?i)(考试作弊|作弊|代写|枪手|泄题|泄露试题|考试答案|正确答案是|答案：|答案是)"),
            Pattern.compile("(?i)(代写论文|代写作业|代做|抄袭|未引用|plagiarism)"),
            Pattern.compile("(?i)(直接给出答案|考试直接答案|期末答案|期中答案)")
    );

    // Direct answer patterns (even if not in sensitive list)
    private static final Pattern DIRECT_ANSWER = Pattern.compile(
            "(?i)(答案是|正确答案|答案：|选择题答案|简答题答案|考试答案|the answer is|correct answer:)"
    );

    public record SafetyResult(boolean passed, String status, String message, double riskScore) {}

    /**
     * Main entry: double-check after Critic.
     */
    public SafetyResult check(String content, double factualityScore, String validationStatus) {
        if (content == null || content.isBlank()) {
            return new SafetyResult(false, "REJECTED", "内容为空，已拦截", 1.0);
        }

        double risk = 0.0;
        StringBuilder reasons = new StringBuilder();

        // 1. Factuality gate (Critic already computed)
        if (factualityScore < 0.70) {
            // Factuality is a hard publication gate, not a weak additive signal.
            // A score of 0 used to pass whenever no other keyword was hit.
            risk = factualityScore < 0.45 ? 0.80 : 0.60;
            reasons.append("FactualityScore=").append(String.format("%.2f", factualityScore)).append("(<0.7)；");
        }

        // 2. Academic sensitive keywords
        for (Pattern p : ACADEMIC_SENSITIVE) {
            if (p.matcher(content).find()) {
                risk += 0.35;
                reasons.append("命中学术敏感词；");
                break;
            }
        }

        // 3. Direct exam answer detection (regex)
        if (DIRECT_ANSWER.matcher(content).find() && !looksLikeLegitimatePracticeMaterial(content)) {
            risk += 0.3;
            reasons.append("检测到直接考试答案模式；");
        }

        // 4. Optional LLM Judge for subtle cases (when DeepSeek configured)
        if (deepSeekApiClient != null && deepSeekApiClient.isConfigured() && risk > 0.2) {
            try {
                String judge = deepSeekApiClient.chat(
                        "你是学术内容安全 Judge。只输出 JSON：{\"risk\":0.0-1.0,\"reason\":\"...\"}",
                        "待检查内容（前800字）：\n" + content.substring(0, Math.min(800, content.length())) + "\n\n请判断是否存在直接泄露考试答案、代写痕迹或学术不端内容。",
                        false
                );
                // crude parse
                if (judge.contains("\"risk\"") && judge.contains("0.")) {
                    // keep simple; real impl would parse JSON
                    if (judge.contains("0.7") || judge.contains("0.8") || judge.contains("0.9")) {
                        risk = Math.max(risk, 0.75);
                        reasons.append("LLM Judge 高风险；");
                    }
                }
            } catch (Exception e) {
                log.warn("LLM Judge evaluation failed during content safety check: {}", e.getMessage());
            }
        }

        boolean passed = risk < 0.55;
        String status = passed ? "PASSED" : (risk > 0.75 ? "REJECTED" : "REVISE");
        String msg = reasons.length() > 0 ? reasons.toString() : "通过学术安全双重检查";

        if (!passed) {
            log.warn("[ContentSafetyFilter] Blocked/revise risk={}: {}", risk, msg);
        }

        return new SafetyResult(passed, status, msg, Math.min(1.0, risk));
    }

    private static boolean looksLikeLegitimatePracticeMaterial(String content) {
        return content != null && Pattern.compile(
                "(?i)(练习题|自测题|题目|题库|解析|知识检查|quiz|practice|explanation)"
        ).matcher(content).find();
    }
}
