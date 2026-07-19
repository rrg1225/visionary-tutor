package com.visionary.dto;



import com.visionary.os.LearnerStateStore;



import java.util.List;



public record LearnerStateResponse(

        Long userId,

        int profileVersion,

        int pathVersion,

        String profileSnapshot,

        String learningGoal,

        String lastPolicyReason,

        String updatedAt,

        List<LearningOsEventDto> recentEvents,

        RecommendationPushDto pendingRecommendationPush

) {

    public static LearnerStateResponse from(

            LearnerStateStore.LearnerStateView state,

            List<LearningOsEventDto> events,

            RecommendationPushDto pendingPush

    ) {

        return new LearnerStateResponse(

                state.userId(),

                state.profileVersion(),

                state.pathVersion(),

                state.profileSnapshot(),

                state.learningGoal(),

                state.lastPolicyReason(),

                state.updatedAt(),

                events,

                pendingPush

        );

    }

}

