/**
 * Structured AI practice quiz (schema generated-quiz/v1).
 *
 * Parses the structured JSON persisted in `GeneratedArtifact.contentJson` and grades it
 * with the same rules as the backend `FixedExamAttemptService.grade`:
 * - SINGLE_CHOICE: normalized equality against the option key
 * - other types: a scoring point is achieved when any accepted keyword appears in the
 *   normalized answer; the question is correct when every scoring point is achieved.
 *
 * Legacy Markdown quizzes are intentionally read-only: automatic grading is available
 * only when this parser returns a validated structured document.
 */

const SCHEMA_PREFIX = "generated-quiz/";

const TYPE_LABELS = {
  SINGLE_CHOICE: "选择题",
  SHORT_ANSWER: "简答题",
  CALCULATION: "计算题",
  CODE_DEBUGGING: "代码纠错",
  MULTI_STEP: "综合题",
};

function normalize(value) {
  return String(value || "")
    .toLowerCase()
    .replace(/\s+/g, "")
    .replaceAll("，", ",")
    .replaceAll("。", ".");
}

/**
 * @returns {Array|null} normalized questions, or null when the payload is not a
 *   structured quiz (caller should fall back to the legacy Markdown parser).
 */
export function parseGeneratedQuiz(contentJson) {
  if (!contentJson || typeof contentJson !== "string") return null;
  let document;
  try {
    document = JSON.parse(contentJson);
  } catch {
    return null;
  }
  if (!String(document?.schema || "").startsWith(SCHEMA_PREFIX)) return null;
  if (!Array.isArray(document.questions) || !document.questions.length)
    return null;

  return document.questions.map((question, index) => ({
    id: String(question.id || `q${index + 1}`),
    index: index + 1,
    structured: true,
    type: question.type === "SINGLE_CHOICE" ? "choice" : "short",
    rawType: question.type,
    typeLabel: TYPE_LABELS[question.type] || "练习题",
    difficulty: question.difficulty || "",
    knowledgePoints: Array.isArray(question.knowledgePoints)
      ? question.knowledgePoints
      : [],
    prompt: String(question.prompt || ""),
    options: Array.isArray(question.options)
      ? question.options.map((option) => ({
          key: String(option.key || ""),
          text: String(option.text || ""),
        }))
      : [],
    answer: String(question.standardAnswer || ""),
    scoringPoints: Array.isArray(question.scoringPoints)
      ? question.scoringPoints.map((point) => ({
          description: String(point.description || ""),
          acceptedKeywords: Array.isArray(point.acceptedKeywords)
            ? point.acceptedKeywords
            : [],
        }))
      : [],
    explanation: String(question.explanation || ""),
    commonErrors: Array.isArray(question.commonErrors)
      ? question.commonErrors
      : [],
    recommendedReview: String(question.recommendedReview || ""),
  }));
}

/** Grades one structured question; mirrors the backend scoring-point keyword rule. */
export function gradeGeneratedQuestion(question, userAnswer) {
  const answerText = String(userAnswer || "").trim();
  if (question.type === "choice") {
    const correct =
      Boolean(answerText) &&
      normalize(answerText) === normalize(question.answer);
    return { correct, scoringPoints: [] };
  }
  const normalizedAnswer = normalize(answerText);
  const scoringPoints = question.scoringPoints.map((point) => ({
    description: point.description,
    achieved:
      Boolean(answerText) &&
      point.acceptedKeywords.some((keyword) =>
        normalizedAnswer.includes(normalize(keyword)),
      ),
  }));
  const correct =
    scoringPoints.length > 0 && scoringPoints.every((point) => point.achieved);
  return { correct, scoringPoints };
}

/** Stable result shape consumed by learning-path and wrong-book submissions. */
export function gradeGeneratedQuiz(questions, answers) {
  let correct = 0;
  let answered = 0;
  const wrongTopics = [];
  const errorPatterns = [];
  const results = {};

  questions.forEach((question) => {
    const userAnswer = String(answers[question.id] || "").trim();
    const result = gradeGeneratedQuestion(question, userAnswer);
    results[question.id] = result;
    if (!userAnswer) return;
    answered += 1;
    if (result.correct) {
      correct += 1;
    } else {
      wrongTopics.push(...question.knowledgePoints.slice(0, 2));
      errorPatterns.push(`${question.prompt.slice(0, 24)} → 未覆盖全部得分点`);
    }
  });

  const accuracy = answered > 0 ? correct / answered : 0;
  return { correct, answered, accuracy, wrongTopics, errorPatterns, results };
}
