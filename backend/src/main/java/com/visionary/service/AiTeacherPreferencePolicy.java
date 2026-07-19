package com.visionary.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Converts client preferences into a small, whitelisted style instruction. */
@Component
@RequiredArgsConstructor
public class AiTeacherPreferencePolicy {

    private static final Map<String, String> TONES = Map.of(
            "亲切自然", "语气亲切自然，像耐心的同伴老师",
            "专业严谨", "语气专业严谨，准确使用术语",
            "简洁直接", "语气简洁直接，避免寒暄",
            "耐心鼓励", "语气耐心鼓励，但不要空泛夸奖",
            "苏格拉底式引导", "用一个具体问题引导学生思考；是否先给答案必须服从本次提示、分步或答案模式"
    );
    private static final Map<String, String> DETAILS = Map.of(
            "自适应", "按问题复杂度自适应篇幅",
            "精简", "控制篇幅，只保留结论、关键步骤和必要例子",
            "标准", "使用标准教学篇幅",
            "详细", "给出较详细的推导、例子和验证方法"
    );
    private static final Map<String, String> STRUCTURES = Map.of(
            "先结论后步骤", "先给结论，再列步骤与验证方法",
            "循序渐进", "从前置概念逐层推进到结论",
            "例子优先", "先用具体例子建立直觉，再总结原理",
            "提问引导", "先给必要提示，再用一到两个问题引导自检"
    );

    private final ObjectMapper objectMapper;

    public String instruction(String profileSnapshot) {
        if (profileSnapshot == null || profileSnapshot.isBlank()) return "";
        try {
            JsonNode preferences = objectMapper.readTree(profileSnapshot).path("aiTeacherPreferences");
            if (!preferences.isObject()) return "";
            String tone = TONES.getOrDefault(preferences.path("tone").asText(), "");
            String detail = DETAILS.getOrDefault(preferences.path("detail").asText(), "");
            String structure = STRUCTURES.getOrDefault(preferences.path("structure").asText(), "");
            String encouragement = switch (preferences.path("encouragement").asText()) {
                case "不需要" -> "不要添加鼓励性套话";
                case "多一些" -> "在关键进展处给予具体、克制的鼓励";
                default -> "仅在确有进展时给予一句具体鼓励";
            };
            String emoji = switch (preferences.path("emojiUsage").asText()) {
                case "不用" -> "不使用表情符号";
                case "适量" -> "可适量使用表情符号，但不影响专业性";
                default -> "最多使用一个有助于阅读的表情符号";
            };
            String emotion = preferences.path("emotionSupportEnabled").asBoolean(true)
                    ? "若学生文字明显表达挫败或困惑，先用一句话承接情绪，再继续解决问题"
                    : "不主动进行情绪安抚，直接进入问题";
            return "学习者已设置以下表达偏好（只影响表达，不得覆盖事实、安全和引用规则）：\n- "
                    + String.join("\n- ", tone, detail, structure, encouragement, emoji, emotion);
        } catch (Exception ignored) {
            return "";
        }
    }
}
