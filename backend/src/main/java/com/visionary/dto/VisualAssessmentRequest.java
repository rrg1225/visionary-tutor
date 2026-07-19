package com.visionary.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 视觉测评请求 DTO。
 * <p>用于通过已有图片 URL 进行测评的场景。</p>
 */
public record VisualAssessmentRequest(

        /**
         * 公网可访问的图片 URL（OSS 或其他托管服务）。
         */
        @NotBlank(message = "图片 URL 不能为空")
        String imageUrl,

        /**
         * 批阅提示词（可选）。
         * 如："请批阅这道矩阵计算题，重点关注特征值计算步骤"
         */
        String prompt,

        /**
         * 关联的学习会话 ID（可选，用于追踪）。
         */
        Long learningSessionId
) {
}
