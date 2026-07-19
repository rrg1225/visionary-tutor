package com.visionary.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.visionary.agent.AgentTaskType;

import java.util.Map;

/**
 * Unified request body for {@code POST /api/ai/invoke}.
 * <p>采用通用字段 + 扩展字段设计，避免大杂烩平铺字段导致的维护困难。</p>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li><b>通用字段</b>: 所有任务类型都需要的字段（如 taskType, payloadText）</li>
 *   <li><b>扩展字段 payloadExt</b>: 特定任务类型的参数，由 Handler 自行解析</li>
 * </ul>
 *
 * <p>MultipartFile 应通过 Controller 层单独传入，不混入 DTO 避免序列化问题。</p>
 *
 * <p>payloadExt 常用键值约定（非强制，Handler 自行约定）：</p>
 * <ul>
 *   <li>VISUAL_ASSESSMENT: imageBase64, imageUrl, contextPrompt</li>
 *   <li>VOICE_INTERACTION: voiceToken, enableVoice</li>
 *   <li>KNOWLEDGE_DIAGNOSIS: diagnosisId, learnerQuestion</li>
 *   <li>RESOURCE_GENERATION: ragQuery, studentProfileSnapshot</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentInvokeRequest(
        /**
         * 任务类型（可选）。为空时由 Router 根据启发式规则自动判定。
         */
        AgentTaskType taskType,

        /**
         * 主要文本载荷。根据任务类型可能是用户问题、提示词、诊断描述等。
         */
        String payloadText,

        /**
         * 上下文提示词。用于覆盖或增强系统默认提示词。
         */
        String contextPrompt,

        /**
         * 扩展参数字段 - 存放特定任务类型的参数。
         * <p>示例：{"imageBase64": "...", "enableVoice": true}</p>
         */
        Map<String, Object> payloadExt
) {

    /**
     * 从 payloadExt 安全获取字符串值。
     */
    public String getExtString(String key) {
        if (payloadExt == null) {
            return null;
        }
        Object value = payloadExt.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 从 payloadExt 安全获取布尔值。
     */
    public boolean getExtBoolean(String key, boolean defaultValue) {
        if (payloadExt == null) {
            return defaultValue;
        }
        Object value = payloadExt.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return defaultValue;
    }

    /**
     * 从 payloadExt 安全获取指定类型的值。
     */
    @SuppressWarnings("unchecked")
    public <T> T getExt(String key, Class<T> type) {
        if (payloadExt == null) {
            return null;
        }
        Object value = payloadExt.get(key);
        if (value != null && type.isAssignableFrom(value.getClass())) {
            return (T) value;
        }
        return null;
    }

    // ==================== 便捷访问方法（针对高频使用的扩展字段）====================

    public String imageBase64() {
        return getExtString("imageBase64");
    }

    public String imageUrl() {
        return getExtString("imageUrl");
    }

    public String ragQuery() {
        return getExtString("ragQuery");
    }

    public String learnerQuestion() {
        return getExtString("learnerQuestion");
    }

    public String diagnosisId() {
        return getExtString("diagnosisId");
    }

    public String voiceToken() {
        return getExtString("voiceToken");
    }

    public Boolean enableVoice() {
        return getExtBoolean("enableVoice", false);
    }

    public String studentProfileSnapshot() {
        return getExtString("studentProfileSnapshot");
    }

    public Long learningSessionId() {
        if (payloadExt == null) {
            return null;
        }
        Object value = payloadExt.get("learningSessionId");
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    public String weakPointsSnapshot() {
        return getExtString("weakPointsSnapshot");
    }

    public String emotionSnapshot() {
        return getExtString("emotionSnapshot");
    }

    public String sensoryTags() {
        return getExtString("sensoryTags");
    }

    public String facialToken() {
        return getExtString("facialToken");
    }
}
