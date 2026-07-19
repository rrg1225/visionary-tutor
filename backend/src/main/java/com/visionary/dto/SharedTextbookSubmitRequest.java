package com.visionary.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SharedTextbookSubmitRequest(
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String description,
        @JsonAlias({"content", "content_markdown", "markdown"})
        @NotBlank @Size(min = 300, max = 100_000, message = "教材正文需为 300–100000 个字符") String contentMarkdown,
        @JsonAlias({"subject", "subject_tag", "category"})
        @Size(max = 64) String subjectTag,
        @Size(max = 32) String visibility,
        @JsonAlias({"source_type"})
        @NotBlank
        @Pattern(regexp = "original|open_license|authorized|personal_notes", message = "来源类型不受支持")
        String sourceType,
        @JsonAlias({"source_title"})
        @Size(max = 255) String sourceTitle,
        @JsonAlias({"source_url"})
        @Size(max = 1000) String sourceUrl,
        @JsonAlias({"license", "license_name"})
        @Size(max = 128) String licenseName,
        @JsonAlias({"rights_statement"})
        @NotBlank @Size(max = 1000) String rightsStatement,
        @JsonAlias({"rights_confirmed"})
        @NotNull @AssertTrue(message = "请确认你有权提交该材料") Boolean rightsConfirmed
) {
}
