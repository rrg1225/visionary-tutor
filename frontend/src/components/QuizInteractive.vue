<template>
  <section class="quiz-panel" aria-label="互动练习题">
    <header class="quiz-header">
      <strong>互动练习</strong>
      <span v-if="isStructured">
        {{ questions.length }} 题 · 含标准答案与得分点 · 提交后自动更新学习路径
      </span>
      <span v-else>历史资源 · 只读兼容</span>
    </header>

    <section v-if="!isStructured" class="legacy-quiz-notice">
      <strong>这是一份旧版 Markdown 题库</strong>
      <p>
        为避免正则解析和字符串比较造成误判，旧资源不再提供自动评分。你仍可阅读内容，或生成一份经过结构校验的新练习。
      </p>
      <MarkdownPanel :content="content" />
      <RouterLink
        class="vt-btn vt-btn-primary vt-btn-sm"
        to="/questions/personalized"
      >
        生成结构化专项练习
      </RouterLink>
    </section>

    <article
      v-for="question in questions"
      :key="question.id"
      class="quiz-question"
    >
      <div class="question-title">
        <span class="q-index">{{ question.index }}</span>
        <p>{{ question.prompt }}</p>
      </div>
      <p v-if="question.structured" class="question-meta">
        {{ question.typeLabel }}
        <template v-if="question.knowledgePoints.length">
          · 考查：{{ question.knowledgePoints.join("、") }}
        </template>
      </p>

      <div v-if="question.type === 'choice'" class="option-list">
        <label
          v-for="option in question.options"
          :key="option.key"
          class="option-item"
        >
          <input
            v-model="answers[question.id]"
            type="radio"
            :name="question.id"
            :value="option.key"
            :disabled="submitted"
          />
          <span>{{ option.key }}. {{ option.text }}</span>
        </label>
      </div>

      <div v-else class="short-answer">
        <textarea
          v-model="answers[question.id]"
          class="vt-input answer-textarea"
          rows="4"
          placeholder="输入你的答案，可分步骤写出推导过程"
          :disabled="submitted"
        ></textarea>
      </div>

      <div
        v-if="submitted && resultMap[question.id]"
        class="question-feedback"
        :class="resultMap[question.id].correct ? 'ok' : 'bad'"
      >
        <p>
          {{
            resultMap[question.id].correct
              ? "正确"
              : `参考答案：${question.answer || "见解析"}`
          }}
          <span v-if="question.explanation"> · {{ question.explanation }}</span>
        </p>
        <ul
          v-if="resultMap[question.id].scoringPoints?.length"
          class="scoring-point-list"
        >
          <li
            v-for="point in resultMap[question.id].scoringPoints"
            :key="point.description"
            :class="point.achieved ? 'achieved' : 'missed'"
          >
            {{ point.achieved ? "已命中" : "未命中" }} · {{ point.description }}
          </li>
        </ul>
      </div>

      <div v-if="!submitted" class="question-help-actions">
        <button type="button" @click="openTutor(question)">问 AI 老师</button>
        <button type="button" @click="revealAnswer(question)">
          {{ revealedAnswers[question.id] ? "答案已显示" : "直接查看答案" }}
        </button>
      </div>

      <div
        v-if="revealedAnswers[question.id] && !submitted"
        class="revealed-answer"
      >
        <strong
          >参考答案：{{ question.answer || "当前题目缺少结构化答案" }}</strong
        >
        <p v-if="question.explanation">{{ question.explanation }}</p>
        <p v-else>当前题目缺少解析，建议向 AI 老师追问具体步骤。</p>
      </div>
    </article>

    <footer v-if="isStructured" class="quiz-footer">
      <button
        class="vt-btn vt-btn-primary"
        data-testid="quiz-submit-btn"
        :disabled="submitting || submitted || !canSubmit"
        @click="submitQuiz"
      >
        {{ submitting ? "提交中..." : submitted ? "已提交" : "提交练习结果" }}
      </button>
      <p v-if="submitMessage" class="submit-message">{{ submitMessage }}</p>
    </footer>

    <ContextualTutorPanel
      v-if="isStructured && selectedTutorQuestion"
      title="针对当前题目提问"
      :context="tutorContext"
      suggested-question="只给我一个能继续推导的提示，不要直接给答案。"
      :learning-session-id="learningSessionId"
      context-type="GENERATED_QUIZ"
      :context-key="`quiz:${selectedTutorQuestion.id}:${content.length}`"
      :context-title="`专项练习 · 第 ${selectedTutorQuestion.index} 题`"
      :answer-mode="
        revealedAnswers[selectedTutorQuestion.id]
          ? 'ANSWER_REVEALED'
          : 'HINT_ONLY'
      "
      @close="selectedTutorQuestion = null"
    />
  </section>
</template>

<script setup>
import { computed, reactive, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { parseGeneratedQuiz, gradeGeneratedQuiz } from "../utils/generatedQuiz";
import ContextualTutorPanel from "./ContextualTutorPanel.vue";
import MarkdownPanel from "./common/MarkdownPanel.vue";
import { recordQuestionAttempts } from "../api/questionBank";

const props = defineProps({
  content: { type: String, default: "" },
  contentJson: { type: String, default: "" },
  userId: { type: Number, default: null },
  learningSessionId: { type: Number, default: null },
  onSubmit: { type: Function, default: null },
});

const emit = defineEmits(["submitted"]);

const answers = reactive({});
const submitted = ref(false);
const submitting = ref(false);
const submitMessage = ref("");
const resultMap = reactive({});
const revealedAnswers = reactive({});
const skippedRecorded = reactive({});
const selectedTutorQuestion = ref(null);

// 只有 generated-quiz/v1 可以进入自动评分；历史 Markdown 资源仅只读展示。
const structuredQuestions = computed(() =>
  parseGeneratedQuiz(props.contentJson),
);
const isStructured = computed(() => Boolean(structuredQuestions.value?.length));
const questions = computed(() => structuredQuestions.value || []);

const canSubmit = computed(
  () =>
    questions.value.length > 0 &&
    Object.values(answers).some((value) => String(value || "").trim()),
);
const tutorContext = computed(() => {
  const question = selectedTutorQuestion.value;
  if (!question) return "";
  const options =
    question.options
      ?.map((option) => `${option.key}. ${option.text}`)
      .join("\n") || "";
  return [
    `题目：${question.prompt}`,
    options ? `选项：\n${options}` : "",
    answers[question.id] ? `我的答案：${answers[question.id]}` : "我还没有作答",
    revealedAnswers[question.id] && question.answer
      ? `已查看的参考答案：${question.answer}`
      : "",
    revealedAnswers[question.id] && question.explanation
      ? `已查看的解析：${question.explanation}`
      : "",
  ]
    .filter(Boolean)
    .join("\n\n");
});

watch(
  () => [props.content, props.contentJson],
  () => {
    submitted.value = false;
    submitMessage.value = "";
    Object.keys(answers).forEach((key) => delete answers[key]);
    Object.keys(resultMap).forEach((key) => delete resultMap[key]);
    Object.keys(revealedAnswers).forEach((key) => delete revealedAnswers[key]);
    Object.keys(skippedRecorded).forEach((key) => delete skippedRecorded[key]);
    selectedTutorQuestion.value = null;
  },
);

function openTutor(question) {
  selectedTutorQuestion.value = question;
}

async function revealAnswer(question) {
  revealedAnswers[question.id] = true;
  if (!props.userId || skippedRecorded[question.id]) return;
  try {
    await recordQuestionAttempts([
      {
        learningSessionId: props.learningSessionId,
        prompt: question.prompt,
        userAnswer: answers[question.id] || "",
        correctAnswer: question.answer || "",
        explanation: question.explanation || "",
        concept: question.prompt.slice(0, 80),
        correct: false,
        skipped: true,
        sourceType: "AI_PRACTICE",
      },
    ]);
    skippedRecorded[question.id] = true;
  } catch {
    // Revealing an answer remains available even if wrong-book persistence is temporarily offline.
  }
}

async function submitQuiz() {
  if (!isStructured.value) return;
  if (!props.userId || !props.learningSessionId) {
    submitMessage.value = "请先登录并创建学习会话后再提交练习。";
    return;
  }

  submitting.value = true;
  submitMessage.value = "";

  // 与后端 FixedExamAttemptService.grade 相同的规则：选择题等值、其余按得分点关键词命中。
  const grade = gradeGeneratedQuiz(questions.value, answers);
  questions.value.forEach((question) => {
    resultMap[question.id] = grade.results[question.id];
  });

  try {
    const payload = {
      userId: props.userId,
      learningSessionId: props.learningSessionId,
      accuracy: grade.accuracy,
      newWeakPoints: grade.wrongTopics,
      errorPatterns: grade.errorPatterns,
      quizFeedback: `答对 ${grade.correct}/${grade.answered}，正确率 ${Math.round(grade.accuracy * 100)}%`,
    };

    const result = props.onSubmit ? await props.onSubmit(payload) : null;

    if (props.userId) {
      const attempts = questions.value
        .filter((question) => !skippedRecorded[question.id])
        .map((question) => ({
          learningSessionId: props.learningSessionId,
          prompt: question.prompt,
          userAnswer: answers[question.id] || "",
          correctAnswer: question.answer || "",
          explanation: question.explanation || "",
          concept: question.prompt.slice(0, 80),
          correct: Boolean(resultMap[question.id]?.correct),
          skipped: Boolean(revealedAnswers[question.id]),
          sourceType: "AI_PRACTICE",
        }));
      if (attempts.length) {
        await recordQuestionAttempts(attempts).catch(() => null);
      }
    }

    submitted.value = true;
    submitMessage.value =
      result?.message ||
      `练习已提交：正确率 ${Math.round(grade.accuracy * 100)}%${result?.triggered ? "，已触发动态路径重规划" : ""}`;
    emit("submitted", { grade, result });
  } catch (error) {
    submitMessage.value = error?.message || "练习提交失败";
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.quiz-panel {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-primary);
  border: 1px solid var(--vt-border-light);
}

.quiz-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--vt-space-2);
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
}

.quiz-header strong {
  font-size: var(--vt-text-sm);
  color: var(--vt-text-primary);
}

.legacy-quiz-notice {
  display: grid;
  justify-items: start;
  gap: var(--vt-space-3);
  padding: var(--vt-space-4);
  border: 1px solid rgba(245, 158, 11, 0.3);
  border-radius: var(--vt-radius-md);
  background: rgba(245, 158, 11, 0.06);
}

.legacy-quiz-notice p {
  margin: 0;
  color: var(--vt-text-secondary);
  line-height: 1.7;
}

.legacy-quiz-notice :deep(.markdown-body) {
  max-height: 520px;
  overflow: auto;
}

.quiz-question {
  display: grid;
  gap: var(--vt-space-2);
  padding-top: var(--vt-space-2);
  border-top: 1px dashed var(--vt-border-light);
}

.question-title {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--vt-space-2);
  align-items: start;
}

.q-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 999px;
  background: rgba(59, 130, 246, 0.12);
  color: var(--vt-accent-primary);
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
}

.question-title p {
  margin: 0;
  font-size: var(--vt-text-sm);
  line-height: 1.5;
}

.option-list {
  display: grid;
  gap: var(--vt-space-1);
}

.option-item {
  display: flex;
  align-items: flex-start;
  gap: var(--vt-space-2);
  font-size: var(--vt-text-sm);
  cursor: pointer;
}

.short-answer .vt-input {
  width: 100%;
}

.answer-textarea {
  resize: vertical;
  min-height: 88px;
  line-height: 1.6;
  font: inherit;
}

.question-meta {
  margin: 0;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-xs);
}

.question-feedback {
  margin: 0;
  font-size: var(--vt-text-xs);
  line-height: 1.5;
}

.question-feedback p {
  margin: 0;
}

.scoring-point-list {
  margin: 0.3rem 0 0;
  padding-left: 1.1rem;
  display: grid;
  gap: 0.15rem;
}

.scoring-point-list .achieved {
  color: #166534;
}

.scoring-point-list .missed {
  color: #b91c1c;
}

.question-feedback.ok {
  color: #166534;
}
.question-feedback.bad {
  color: #b91c1c;
}

.question-help-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}

.question-help-actions button {
  padding: 5px 9px;
  border: 1px solid var(--vt-border-light);
  border-radius: 999px;
  background: var(--vt-surface);
  color: var(--vt-text-secondary);
  cursor: pointer;
  font: inherit;
  font-size: var(--vt-text-xs);
}

.question-help-actions button:hover {
  border-color: rgba(13, 148, 136, 0.4);
  color: var(--vt-accent-teal-dark);
}

.revealed-answer {
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: rgba(245, 158, 11, 0.08);
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-xs);
  line-height: 1.6;
}

.revealed-answer p {
  margin: var(--vt-space-1) 0 0;
}

.quiz-footer {
  display: grid;
  gap: var(--vt-space-2);
}

.submit-message {
  margin: 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
}
</style>
