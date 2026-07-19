package com.visionary.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiStreamTutoringModeTest {
    @Test
    void explicitModesOverrideAutomaticTeachingStyleWithoutLeakingExamAnswers() {
        assertTrue(AiStreamController.tutoringModeInstruction("HINT", "ASSESSMENT_SUPPORT").contains("禁止给最终答案"));
        assertTrue(AiStreamController.tutoringModeInstruction("STEP_BY_STEP", "TUTORING").contains("本轮只讲一个步骤"));
        String direct = AiStreamController.tutoringModeInstruction("DIRECT_ANSWER", "ASSESSMENT_SUPPORT");
        assertTrue(direct.contains("明确结论"));
        assertTrue(direct.contains("考试防泄题规则"));
    }
}
