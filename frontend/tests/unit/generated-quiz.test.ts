import { describe, expect, it } from "vitest";
import {
  parseGeneratedQuiz,
  gradeGeneratedQuestion,
  gradeGeneratedQuiz,
} from "../../src/utils/generatedQuiz";

const STRUCTURED_QUIZ = JSON.stringify({
  schema: "generated-quiz/v1",
  topic: "卷积输出尺寸",
  difficulty: "INTERMEDIATE",
  questions: [
    {
      id: "q1",
      order: 1,
      type: "SINGLE_CHOICE",
      difficulty: "BASIC",
      knowledgePoints: ["输出尺寸公式"],
      prompt: "5×5 输入、3×3 卷积核、padding=0、stride=1，输出尺寸是？",
      options: [
        { key: "A", text: "3×3" },
        { key: "B", text: "5×5" },
      ],
      standardAnswer: "A",
      scoringPoints: [],
      explanation: "输出尺寸 = (5-3+0)/1 + 1 = 3。",
      commonErrors: ["忘记加 1"],
      recommendedReview: "输出尺寸公式",
    },
    {
      id: "q2",
      order: 2,
      type: "SHORT_ANSWER",
      difficulty: "INTERMEDIATE",
      knowledgePoints: ["padding"],
      prompt: "解释 padding 的作用。",
      options: [],
      standardAnswer: "padding 在输入四周补值，保持尺寸并保留边缘信息。",
      scoringPoints: [
        { description: "提到保持尺寸", acceptedKeywords: ["尺寸", "大小"] },
        { description: "提到边缘信息", acceptedKeywords: ["边缘"] },
      ],
      explanation: "padding 让卷积核能覆盖边缘像素。",
      commonErrors: [],
      recommendedReview: "padding 一节",
    },
  ],
});

describe("parseGeneratedQuiz", () => {
  it("parses a structured quiz into normalized questions", () => {
    const questions = parseGeneratedQuiz(STRUCTURED_QUIZ);
    expect(questions).toHaveLength(2);
    expect(questions[0]).toMatchObject({
      id: "q1",
      type: "choice",
      structured: true,
      answer: "A",
      typeLabel: "选择题",
    });
    expect(questions[1].type).toBe("short");
    expect(questions[1].scoringPoints).toHaveLength(2);
  });

  it("returns null for legacy markdown and invalid payloads", () => {
    expect(parseGeneratedQuiz("")).toBeNull();
    expect(parseGeneratedQuiz("# 第1题 不是 JSON")).toBeNull();
    expect(
      parseGeneratedQuiz('{"schema":"other/v1","questions":[{}]}'),
    ).toBeNull();
    expect(
      parseGeneratedQuiz('{"schema":"generated-quiz/v1","questions":[]}'),
    ).toBeNull();
  });
});

describe("gradeGeneratedQuestion", () => {
  const questions = parseGeneratedQuiz(STRUCTURED_QUIZ);

  it("grades single choice by normalized equality", () => {
    expect(gradeGeneratedQuestion(questions[0], "A").correct).toBe(true);
    expect(gradeGeneratedQuestion(questions[0], " a ").correct).toBe(true);
    expect(gradeGeneratedQuestion(questions[0], "B").correct).toBe(false);
    expect(gradeGeneratedQuestion(questions[0], "").correct).toBe(false);
  });

  it("grades short answers per scoring point keyword", () => {
    const full = gradeGeneratedQuestion(
      questions[1],
      "padding 保持输出尺寸，还能保留边缘信息",
    );
    expect(full.correct).toBe(true);
    expect(full.scoringPoints.every((point) => point.achieved)).toBe(true);

    const partial = gradeGeneratedQuestion(questions[1], "它可以保持尺寸");
    expect(partial.correct).toBe(false);
    expect(partial.scoringPoints[0].achieved).toBe(true);
    expect(partial.scoringPoints[1].achieved).toBe(false);
  });
});

describe("gradeGeneratedQuiz", () => {
  it("aggregates accuracy and per-question results", () => {
    const questions = parseGeneratedQuiz(STRUCTURED_QUIZ);
    const grade = gradeGeneratedQuiz(questions, {
      q1: "A",
      q2: "只保持尺寸",
    });
    expect(grade.answered).toBe(2);
    expect(grade.correct).toBe(1);
    expect(grade.accuracy).toBe(0.5);
    expect(grade.results.q1.correct).toBe(true);
    expect(grade.results.q2.correct).toBe(false);
    expect(grade.wrongTopics).toContain("padding");
  });
});
