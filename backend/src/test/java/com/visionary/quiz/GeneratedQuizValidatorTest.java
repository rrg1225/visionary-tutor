package com.visionary.quiz;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedQuizValidatorTest {

    @Test
    void validQuizPassesGate() {
        GeneratedQuizDocument document = GeneratedQuizValidator.normalize(new GeneratedQuizDocument(
                GeneratedQuizDocument.SCHEMA_V1,
                "卷积输出尺寸",
                "INTERMEDIATE",
                List.of(choiceQuestion(), shortAnswerQuestion())
        ));
        assertEquals(List.of(), GeneratedQuizValidator.validate(document, 2));
    }

    @Test
    void normalizeFillsMissingIdsAndOrders() {
        GeneratedQuizDocument document = GeneratedQuizValidator.normalize(new GeneratedQuizDocument(
                null,
                " 主题 ",
                "BASIC",
                List.of(withIdentity(shortAnswerQuestion(), null, null))
        ));
        assertEquals(GeneratedQuizDocument.SCHEMA_V1, document.schema());
        assertEquals("主题", document.topic());
        assertEquals("q1", document.questions().get(0).id());
        assertEquals(1, document.questions().get(0).order());
    }

    @Test
    void rejectsChoiceAnswerOutsideOptions() {
        GeneratedQuizDocument.Question broken = new GeneratedQuizDocument.Question(
                "q1", 1, "SINGLE_CHOICE", "BASIC",
                List.of("padding"), "题干",
                List.of(new GeneratedQuizDocument.Option("A", "选项A"), new GeneratedQuizDocument.Option("B", "选项B")),
                "C",
                null, "这是一段足够长度的解析内容。", List.of("常见错误"), "复习"
        );
        List<String> errors = GeneratedQuizValidator.validate(
                GeneratedQuizValidator.normalize(document(broken)), 1);
        assertTrue(errors.stream().anyMatch(error -> error.contains("现有选项")), errors::toString);
    }

    @Test
    void rejectsMissingAnswerAndScoringPoints() {
        GeneratedQuizDocument.Question broken = new GeneratedQuizDocument.Question(
                "q1", 1, "SHORT_ANSWER", "BASIC",
                List.of("padding"), "题干",
                List.of(), "  ",
                List.of(), "太短", List.of(), null
        );
        List<String> errors = GeneratedQuizValidator.validate(
                GeneratedQuizValidator.normalize(document(broken)), 1);
        assertTrue(errors.stream().anyMatch(error -> error.contains("standardAnswer")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.contains("scoringPoints")), errors::toString);
        assertTrue(errors.stream().anyMatch(error -> error.contains("explanation")), errors::toString);
    }

    @Test
    void rejectsWrongQuestionCount() {
        GeneratedQuizDocument document = GeneratedQuizValidator.normalize(document(shortAnswerQuestion()));
        List<String> errors = GeneratedQuizValidator.validate(document, 3);
        assertTrue(errors.stream().anyMatch(error -> error.contains("题目数量")), errors::toString);
    }

    @Test
    void rendererProducesLegacyCompatibleMarkdown() {
        GeneratedQuizDocument document = GeneratedQuizValidator.normalize(new GeneratedQuizDocument(
                GeneratedQuizDocument.SCHEMA_V1, "卷积", "BASIC",
                List.of(choiceQuestion())
        ));
        String markdown = GeneratedQuizMarkdownRenderer.render(document);
        assertTrue(markdown.contains("第1题"));
        assertTrue(markdown.contains("答案：A"));
        assertTrue(markdown.contains("解析："));
        assertTrue(markdown.contains("A. 3×3"));
    }

    private static GeneratedQuizDocument document(GeneratedQuizDocument.Question question) {
        return new GeneratedQuizDocument(GeneratedQuizDocument.SCHEMA_V1, "主题", "BASIC", List.of(question));
    }

    private static GeneratedQuizDocument.Question choiceQuestion() {
        return new GeneratedQuizDocument.Question(
                "q1", 1, "SINGLE_CHOICE", "BASIC",
                List.of("卷积输出尺寸"),
                "5×5 输入、3×3 卷积核、padding=0、stride=1 时输出尺寸是？",
                List.of(new GeneratedQuizDocument.Option("A", "3×3"), new GeneratedQuizDocument.Option("B", "5×5")),
                "A",
                null,
                "输出尺寸 = (5 - 3 + 0) / 1 + 1 = 3，所以是 3×3。",
                List.of("忘记加 1"),
                "重读输出尺寸公式"
        );
    }

    private static GeneratedQuizDocument.Question shortAnswerQuestion() {
        return new GeneratedQuizDocument.Question(
                "q2", 2, "SHORT_ANSWER", "INTERMEDIATE",
                List.of("padding"),
                "解释 padding 的作用。",
                List.of(),
                "padding 在输入四周补值，保持空间尺寸并保留边缘信息。",
                List.of(new GeneratedQuizDocument.ScoringPoint("提到保持尺寸", List.of("尺寸", "大小")),
                        new GeneratedQuizDocument.ScoringPoint("提到边缘信息", List.of("边缘"))),
                "padding 让卷积核能覆盖边缘像素，从而控制输出尺寸。",
                List.of("认为 padding 只是填 0 没有作用"),
                "重读 padding 一节"
        );
    }

    private static GeneratedQuizDocument.Question withIdentity(
            GeneratedQuizDocument.Question base, String id, Integer order) {
        return new GeneratedQuizDocument.Question(
                id, order, base.type(), base.difficulty(), base.knowledgePoints(), base.prompt(),
                base.options(), base.standardAnswer(), base.scoringPoints(), base.explanation(),
                base.commonErrors(), base.recommendedReview()
        );
    }
}
