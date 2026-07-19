package com.visionary.quiz;

import java.util.List;

/**
 * Renders a validated {@link GeneratedQuizDocument} to display Markdown.
 *
 * <p>The Markdown copy lives in {@code content_markdown} for reading, export and legacy
 * consumers; grading always uses the structured JSON in {@code content_json}. The layout
 * intentionally follows the historical "第N题 / 答案： / 解析：" convention so the legacy
 * regex parser can still read new artifacts as a last-resort fallback.</p>
 */
public final class GeneratedQuizMarkdownRenderer {

    private GeneratedQuizMarkdownRenderer() {
    }

    public static String render(GeneratedQuizDocument document) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(blankTo(document.topic(), "专项练习")).append(" 练习题\n\n");
        List<GeneratedQuizDocument.Question> questions = document.questions();
        for (int index = 0; index < questions.size(); index++) {
            GeneratedQuizDocument.Question question = questions.get(index);
            markdown.append("## 第").append(index + 1).append("题（")
                    .append(typeLabel(question.type()))
                    .append(" · ").append(difficultyLabel(question.difficulty()))
                    .append("）\n\n");
            markdown.append(question.prompt()).append("\n\n");
            if (question.options() != null && !question.options().isEmpty()) {
                for (GeneratedQuizDocument.Option option : question.options()) {
                    markdown.append(option.key()).append(". ").append(option.text()).append('\n');
                }
                markdown.append('\n');
            }
            markdown.append("- 考查知识点：").append(String.join("、", question.knowledgePoints())).append('\n');
            markdown.append("- 答案：").append(question.standardAnswer()).append('\n');
            if (question.scoringPoints() != null && !question.scoringPoints().isEmpty()) {
                markdown.append("- 得分点：\n");
                for (GeneratedQuizDocument.ScoringPoint point : question.scoringPoints()) {
                    markdown.append("  - ").append(point.description()).append('\n');
                }
            }
            markdown.append("- 解析：").append(question.explanation()).append('\n');
            if (question.commonErrors() != null && !question.commonErrors().isEmpty()) {
                markdown.append("- 常见错误：").append(String.join("；", question.commonErrors())).append('\n');
            }
            if (question.recommendedReview() != null && !question.recommendedReview().isBlank()) {
                markdown.append("- 推荐复习：").append(question.recommendedReview()).append('\n');
            }
            markdown.append('\n');
        }
        return markdown.toString().trim();
    }

    private static String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case "SINGLE_CHOICE" -> "选择题";
            case "SHORT_ANSWER" -> "简答题";
            case "CALCULATION" -> "计算题";
            case "CODE_DEBUGGING" -> "代码纠错";
            case "MULTI_STEP" -> "综合题";
            default -> "练习题";
        };
    }

    private static String difficultyLabel(String difficulty) {
        return switch (difficulty == null ? "" : difficulty) {
            case "BASIC" -> "基础";
            case "INTERMEDIATE" -> "进阶";
            case "ADVANCED" -> "综合";
            default -> "进阶";
        };
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
