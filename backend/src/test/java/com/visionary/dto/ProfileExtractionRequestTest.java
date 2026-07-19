package com.visionary.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileExtractionRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsStructuredSnapshotsAndSnakeCaseAliases() throws Exception {
        ProfileExtractionRequest request = objectMapper.readValue("""
                {
                  "user_id": 12,
                  "conversation": [{"role":"user","content":"卷积怎么计算"}],
                  "assessment_summary": {"score": 0, "completed": false},
                  "previous_profile_snapshot": {"weakPoints":["卷积步长"]},
                  "emotion_snapshot": {"state":"confused"},
                  "phase": "INCREMENTAL",
                  "future_field": true
                }
                """, ProfileExtractionRequest.class);

        assertThat(request.userId()).isEqualTo(12L);
        assertThat(request.conversationText()).contains("卷积怎么计算");
        assertThat(request.assessmentSummary()).contains("\"score\":0");
        assertThat(request.previousProfileSnapshot()).contains("卷积步长");
        assertThat(request.emotionSnapshot()).contains("confused");
        assertThat(request.extractPhase()).isEqualTo("INCREMENTAL");
    }
}
