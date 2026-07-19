package com.visionary.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.visionary.config.FlexibleStringDeserializer;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfileExtractionRequest(
        @JsonAlias({"user_id"})
        Long userId,
        @JsonAlias({"conversation", "conversation_text"})
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String conversationText,
        @JsonAlias({"assessment", "assessment_summary"})
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String assessmentSummary,
        @JsonAlias({"previous_profile_snapshot", "profileSnapshot"})
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String previousProfileSnapshot,
        @JsonAlias({"emotion_snapshot"})
        @JsonDeserialize(using = FlexibleStringDeserializer.class)
        String emotionSnapshot,
        @JsonAlias({"extract_phase", "phase"})
        String extractPhase
) {
    public ProfileExtractionRequest(
            Long userId,
            String conversationText,
            String assessmentSummary,
            String previousProfileSnapshot,
            String emotionSnapshot
    ) {
        this(userId, conversationText, assessmentSummary, previousProfileSnapshot, emotionSnapshot, "FULL");
    }
}
