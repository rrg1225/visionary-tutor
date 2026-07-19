package com.visionary.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OnboardingAnswerValidationRequest(
        @Min(value = 0, message = "建档步骤不正确")
        @Max(value = 3, message = "建档步骤不正确")
        int stepIndex,

        @NotBlank(message = "建档问题不能为空")
        @Size(max = 500, message = "建档问题过长")
        String question,

        @NotBlank(message = "回答不能为空")
        @Size(max = 1000, message = "回答不能超过 1000 个字符")
        String answer
) {
}
