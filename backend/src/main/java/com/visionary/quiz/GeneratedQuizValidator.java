package com.visionary.quiz;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic quality gate for AI-generated quizzes (问题9).
 *
 * <p>Validation is code, not another LLM call: every check here is a hard assertion the
 * generation must satisfy before the quiz may be shown to a learner. Failures are returned
 * as human-readable messages that are fed back to the model for one repair round.</p>
 */
public final class GeneratedQuizValidator {

    private static final int MIN_EXPLANATION_CHARS = 10;

    private GeneratedQuizValidator() {
    }

    /**
     * Returns a normalized copy: trimmed identity fields, question ids/orders filled in
     * sequence when the model omitted them. Content fields are never invented.
     */
    public static GeneratedQuizDocument normalize(GeneratedQuizDocument document) {
        if (document == null || document.questions() == null) {
            return document;
        }
        List<GeneratedQuizDocument.Question> normalized = new ArrayList<>();
        for (int index = 0; index < document.questions().size(); index++) {
            GeneratedQuizDocument.Question question = document.questions().get(index);
            if (question == null) {
                normalized.add(null);
                continue;
            }
            String id = isBlank(question.id()) ? "q" + (index + 1) : question.id().trim();
            Integer order = question.order() == null ? index + 1 : question.order();
            normalized.add(new GeneratedQuizDocument.Question(
                    id,
                    order,
                    trimOrNull(question.type()),
                    trimOrNull(question.difficulty()),
                    question.knowledgePoints(),
                    trimOrNull(question.prompt()),
                    question.options(),
                    trimOrNull(question.standardAnswer()),
                    question.scoringPoints(),
                    trimOrNull(question.explanation()),
                    question.commonErrors(),
                    trimOrNull(question.recommendedReview())
            ));
        }
        return new GeneratedQuizDocument(
                isBlank(document.schema()) ? GeneratedQuizDocument.SCHEMA_V1 : document.schema().trim(),
                trimOrNull(document.topic()),
                trimOrNull(document.difficulty()),
                List.copyOf(normalized)
        );
    }

    /** Validates a normalized document; an empty result means the quiz passed the gate. */
    public static List<String> validate(GeneratedQuizDocument document, int expectedQuestionCount) {
        List<String> errors = new ArrayList<>();
        if (document == null) {
            errors.add("文档为空，无法解析出题库结构");
            return errors;
        }
        if (document.questions() == null || document.questions().isEmpty()) {
            errors.add("questions 为空，至少需要 1 道题");
            return errors;
        }
        if (expectedQuestionCount > 0 && document.questions().size() != expectedQuestionCount) {
            errors.add("题目数量应为 " + expectedQuestionCount + " 道，实际 " + document.questions().size() + " 道");
        }
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < document.questions().size(); index++) {
            validateQuestion(document.questions().get(index), index + 1, ids, errors);
        }
        return errors;
    }

    private static void validateQuestion(
            GeneratedQuizDocument.Question question,
            int position,
            Set<String> ids,
            List<String> errors
    ) {
        String label = "第 " + position + " 题";
        if (question == null) {
            errors.add(label + "：内容为空");
            return;
        }
        if (isBlank(question.id()) || !ids.add(question.id().toLowerCase(Locale.ROOT))) {
            errors.add(label + "：id 缺失或与其他题目重复");
        }
        if (isBlank(question.prompt())) {
            errors.add(label + "：缺少题干 prompt");
        }
        if (isBlank(question.type()) || !GeneratedQuizDocument.QUESTION_TYPES.contains(question.type())) {
            errors.add(label + "：type 必须是 " + String.join("/", GeneratedQuizDocument.QUESTION_TYPES) + " 之一");
        }
        if (question.knowledgePoints() == null || question.knowledgePoints().stream().allMatch(GeneratedQuizValidator::isBlank)) {
            errors.add(label + "：缺少考查知识点 knowledgePoints");
        }
        if (isBlank(question.standardAnswer())) {
            errors.add(label + "：standardAnswer 不能为空");
        }
        if (isBlank(question.explanation()) || question.explanation().length() < MIN_EXPLANATION_CHARS) {
            errors.add(label + "：explanation 缺失或过短（至少 " + MIN_EXPLANATION_CHARS + " 字）");
        }

        if ("SINGLE_CHOICE".equals(question.type())) {
            validateChoice(question, label, errors);
        } else if (!isBlank(question.type())) {
            validateScoringPoints(question, label, errors);
        }
    }

    private static void validateChoice(GeneratedQuizDocument.Question question, String label, List<String> errors) {
        if (question.options() == null || question.options().size() < 2) {
            errors.add(label + "：选择题至少需要 2 个选项");
            return;
        }
        Set<String> keys = new HashSet<>();
        for (GeneratedQuizDocument.Option option : question.options()) {
            if (option == null || isBlank(option.key()) || isBlank(option.text())) {
                errors.add(label + "：存在缺少 key 或 text 的选项");
                return;
            }
            if (!keys.add(option.key().trim().toUpperCase(Locale.ROOT))) {
                errors.add(label + "：选项 key 重复：" + option.key());
                return;
            }
        }
        if (isBlank(question.standardAnswer())
                || !keys.contains(question.standardAnswer().trim().toUpperCase(Locale.ROOT))) {
            errors.add(label + "：选择题答案必须是现有选项之一，当前答案：" + question.standardAnswer());
        }
    }

    private static void validateScoringPoints(GeneratedQuizDocument.Question question, String label, List<String> errors) {
        if (question.scoringPoints() == null || question.scoringPoints().isEmpty()) {
            errors.add(label + "：非选择题必须提供得分点 scoringPoints");
            return;
        }
        for (GeneratedQuizDocument.ScoringPoint point : question.scoringPoints()) {
            if (point == null || isBlank(point.description())) {
                errors.add(label + "：存在缺少 description 的得分点");
                return;
            }
            if (point.acceptedKeywords() == null
                    || point.acceptedKeywords().stream().allMatch(GeneratedQuizValidator::isBlank)) {
                errors.add(label + "：得分点「" + point.description() + "」缺少 acceptedKeywords 判分关键词");
                return;
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimOrNull(String value) {
        return value == null ? null : value.trim();
    }
}
