package com.visionary.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserFeedbackRequest(
        @JsonAlias({"type", "feedbackType"})
        @NotBlank(message = "请选择反馈类型")
        @Size(max = 32, message = "反馈类型过长")
        @Pattern(regexp = "BUG|UX|CONTENT|SUGGESTION", message = "反馈类型不受支持")
        String category,

        @NotBlank(message = "请填写反馈内容")
        @Size(min = 5, max = 2000, message = "反馈内容应在 5-2000 个字符之间")
        @JsonAlias({"content", "details", "description"})
        String message,

        @JsonAlias({"email", "contactInfo"})
        @Size(max = 128, message = "联系方式不能超过 128 个字符")
        String contact,

        @Size(max = 256, message = "页面路径不能超过 256 个字符")
        @JsonAlias({"path", "page", "page_path"})
        String pagePath
) {
}
