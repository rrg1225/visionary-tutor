package com.visionary.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class QuizResultListener {

    private final ReplanTriggerService replanTriggerService;
    private final LearningMasteryPipelineService learningMasteryPipelineService;

    public ReplanTriggerService.ReplanResult onQuizSubmitted(Long userId, Long learningSessionId,
                                                             double accuracy,
                                                             List<String> newWeakPoints,
                                                             List<String> errorPatterns,
                                                             String quizFeedback) {
        log.info("QuizResultListener received submission: user={}, session={}, accuracy={}",
                userId, learningSessionId, accuracy);

        learningMasteryPipelineService.onQuizSubmitted(
                userId,
                learningSessionId,
                accuracy,
                newWeakPoints,
                "quiz_submission"
        );

        return replanTriggerService.triggerAfterQuiz(
                userId, learningSessionId, accuracy, newWeakPoints, errorPatterns, quizFeedback
        );
    }
}
