<template>
  <section class="exam-page vt-container vt-section">
    <div v-if="loading" class="vt-card exam-state">
      正在加载固定题卷与历史草稿…
    </div>
    <div v-else-if="error" class="vt-card exam-state" role="alert">
      <strong>题卷无法打开</strong>
      <p>{{ error }}</p>
      <RouterLink class="vt-btn vt-btn-outline" to="/questions"
        >返回题库</RouterLink
      >
    </div>

    <template v-else-if="paper && attempt">
      <header class="exam-header vt-card">
        <div>
          <span class="vt-eyebrow">平台精选综合题卷 · 校审版</span>
          <h1>{{ paper.summary.title }}</h1>
          <p>{{ paper.summary.description }}</p>
        </div>
        <div class="exam-timers" aria-label="题卷计时">
          <span
            >整卷用时 <strong>{{ formatDuration(totalSeconds) }}</strong></span
          >
          <span
            >当前题 <strong>{{ formatDuration(currentDuration) }}</strong></span
          >
        </div>
      </header>

      <LearningStateAssist
        context-type="FIXED_EXAM"
        :context-key="stateContextKey"
        :context-title="paper.summary.title"
        :sample-marker="currentQuestion?.id || ''"
      />

      <div class="exam-layout">
        <aside class="question-navigation vt-card" aria-label="题号导航">
          <header>
            <strong>题号</strong>
            <span>{{ answeredCount }}/{{ paper.questions.length }} 已答</span>
          </header>
          <div class="question-numbers">
            <button
              v-for="question in paper.questions"
              :key="question.id"
              type="button"
              :class="{
                current: question.id === currentQuestion.id,
                answered: isAnswered(question.id),
                revealed: revealedAnswers[question.id],
              }"
              :aria-label="`第 ${question.order} 题，${isAnswered(question.id) ? '已答' : '未答'}`"
              @click="goToQuestion(question.id)"
            >
              {{ question.order }}
              <small>{{ isAnswered(question.id) ? "已答" : "未答" }}</small>
            </button>
          </div>
          <dl>
            <div>
              <dt>题量</dt>
              <dd>{{ paper.summary.questionCount }}</dd>
            </div>
            <div>
              <dt>总分</dt>
              <dd>{{ paper.summary.maxScore }}</dd>
            </div>
            <div>
              <dt>建议</dt>
              <dd>{{ paper.summary.durationMinutes }} 分钟</dd>
            </div>
          </dl>
          <p class="save-state" role="status">{{ saveStateText }}</p>
        </aside>

        <main class="question-workspace vt-card">
          <header class="question-heading">
            <div>
              <span class="question-index"
                >第 {{ currentQuestion.order }} 题</span
              >
              <span
                >{{ questionTypeLabel(currentQuestion.type) }} ·
                {{ currentQuestion.maxScore }} 分</span
              >
            </div>
            <div class="knowledge-tags">
              <span
                v-for="point in currentQuestion.knowledgePoints"
                :key="point"
                >{{ point }}</span
              >
            </div>
          </header>

          <MarkdownPanel
            class="question-prompt"
            :content="currentQuestion.prompt"
          />

          <ol v-if="currentQuestion.subQuestions.length" class="sub-questions">
            <li v-for="item in currentQuestion.subQuestions" :key="item">
              {{ item }}
            </li>
          </ol>

          <fieldset
            v-if="currentQuestion.type === 'SINGLE_CHOICE'"
            class="choice-list"
          >
            <legend>选择一个答案</legend>
            <label v-for="option in currentQuestion.options" :key="option.key">
              <input
                v-model="answers[currentQuestion.id]"
                type="radio"
                :name="currentQuestion.id"
                :value="option.key"
                @change="scheduleSave(currentQuestion.id)"
              />
              <strong>{{ option.key }}.</strong>
              <span>{{ option.text }}</span>
            </label>
          </fieldset>

          <label v-else class="answer-editor">
            <span>{{
              currentQuestion.type.startsWith("CODE_") ? "代码与说明" : "作答区"
            }}</span>
            <textarea
              v-model="answers[currentQuestion.id]"
              :class="{
                'code-answer': currentQuestion.type.startsWith('CODE_'),
              }"
              rows="12"
              placeholder="写出推导、关键步骤和最终结论。代码题请同时说明根因与验证方式。"
              @input="scheduleSave(currentQuestion.id)"
            ></textarea>
          </label>

          <section v-if="currentQuestion.type.startsWith('CODE_')" class="code-validation" aria-label="浏览器代码验证">
            <div class="code-validation__heading">
              <div>
                <strong>浏览器 Python 沙箱</strong>
                <p>只在你的浏览器内运行，最长 30 秒；网络、进程和危险模块已禁用。</p>
              </div>
              <button type="button" class="vt-btn vt-btn-outline" :disabled="runningCode" @click="runCurrentCode">
                {{ runningCode ? '正在运行…' : '运行当前代码' }}
              </button>
            </div>
            <pre v-if="currentSandboxReport" :class="['sandbox-report', `status-${String(currentSandboxReport.status).toLowerCase()}`]">{{ sandboxReportText }}</pre>
            <div v-if="currentQuestion.testCases?.length" class="verification-criteria">
              <strong>题目验收条件</strong>
              <p>下列旧题数据是框架级验收条件，不会伪装成已自动通过；可执行用例必须提供 assertion 字段后才会逐项进入沙箱。</p>
              <ul>
                <li v-for="(testCase, index) in currentQuestion.testCases" :key="index">
                  <span>{{ testCase.assertion ? '自动执行' : '需框架环境' }}</span>
                  <code>{{ testCase.input }}</code>
                  <small>期望：{{ testCase.expected }}</small>
                </li>
              </ul>
            </div>
          </section>

          <section
            v-if="revealedAnswers[currentQuestion.id]"
            class="revealed-review"
            aria-label="标准答案与解析"
          >
            <span class="answer-warning">已记录：作答提交前查看过答案</span>
            <h2>标准答案与解析</h2>
            <p>
              <strong>标准答案：</strong
              >{{ revealedAnswers[currentQuestion.id].standardAnswer }}
            </p>
            <ul>
              <li
                v-for="point in revealedAnswers[currentQuestion.id]
                  .scoringPoints"
                :key="point.id"
              >
                {{ point.description }}（{{ point.points }} 分）
              </li>
            </ul>
            <MarkdownPanel
              :content="revealedAnswers[currentQuestion.id].explanation"
            />
          </section>

          <div class="question-actions">
            <button
              type="button"
              class="vt-btn vt-btn-ghost"
              :disabled="currentIndex === 0"
              @click="moveQuestion(-1)"
            >
              上一题
            </button>
            <button
              type="button"
              class="vt-btn vt-btn-outline"
              @click="saveCurrentAnswer"
            >
              保存答案
            </button>
            <button
              type="button"
              class="vt-btn vt-btn-outline"
              :disabled="Boolean(revealedAnswers[currentQuestion.id])"
              @click="revealCurrentAnswer"
            >
              {{
                revealedAnswers[currentQuestion.id]
                  ? "答案已显示"
                  : "查看标准答案与解析"
              }}
            </button>
            <button
              type="button"
              class="vt-btn vt-btn-outline"
              @click="askTutor('只给提示，不要直接给答案。')"
            >
              只给提示
            </button>
            <button
              type="button"
              class="vt-btn vt-btn-outline"
              @click="askTutor('检查我的思路，指出第一处需要修改的位置。')"
            >
              问 AI 老师
            </button>
            <button
              type="button"
              class="vt-btn vt-btn-primary"
              :disabled="currentIndex === paper.questions.length - 1"
              @click="moveQuestion(1)"
            >
              下一题
            </button>
          </div>

          <footer class="exam-submit">
            <div>
              <strong>整套题卷完成后统一提交</strong>
              <span v-if="unansweredCount"
                >还有 {{ unansweredCount }} 题未作答，提交前会再次提醒。</span
              >
              <span v-else>所有题目均已有答案。</span>
            </div>
            <button
              type="button"
              class="vt-btn vt-btn-primary"
              :disabled="submitting"
              @click="submitExam"
            >
              {{ submitting ? "正在评分并生成报告…" : "提交整套题卷" }}
            </button>
          </footer>
        </main>

        <aside class="tutor-column">
          <ContextualTutorPanel
            v-if="tutorVisible"
            :key="tutorKey"
            title="固定题卷 AI 老师"
            :context="tutorContext"
            :suggested-question="tutorQuestion"
            :learning-session-id="learningSession.currentSessionId"
            context-type="FIXED_EXAM"
            :context-key="`${paper.summary.code}:${currentQuestion.id}`"
            :context-title="`${paper.summary.title} · 第 ${currentQuestion.order} 题`"
            :answer-mode="
              revealedAnswers[currentQuestion.id]
                ? 'ANSWER_REVEALED'
                : 'HINT_ONLY'
            "
            @close="tutorVisible = false"
          />
          <button
            v-else
            type="button"
            class="vt-btn vt-btn-primary reopen-tutor"
            @click="tutorVisible = true"
          >
            打开 AI 老师
          </button>
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup>
import LearningStateAssist from "../components/LearningStateAssist.vue";
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import ContextualTutorPanel from "../components/ContextualTutorPanel.vue";
import MarkdownPanel from "../components/common/MarkdownPanel.vue";
import {
  getFixedExamPaper,
  revealFixedExamAnswer,
  saveFixedExamAnswer,
  startFixedExamAttempt,
  submitFixedExamAttempt,
} from "../api/fixedExams";
import { useLearningSessionStore } from "../stores/learningSession";
import { toastError, toastSuccess } from "../utils/toast";
import { runPythonInBrowser } from "../utils/pyodideSandbox";

const route = useRoute();
const router = useRouter();
const learningSession = useLearningSessionStore();
const paper = ref(null);
const attempt = ref(null);
const loading = ref(true);
const error = ref("");
const submitting = ref(false);
const currentQuestionId = ref("");
const answers = reactive({});
const durationByQuestion = reactive({});
const revealedAnswers = reactive({});
const saveState = ref("idle");
const totalSeconds = ref(0);
const tutorVisible = ref(true);
const tutorQuestion = ref("只给提示，不要直接给答案。");
const tutorKey = ref(0);
const saveTimers = new Map();
const sandboxReports = reactive({});
const runningQuestionId = ref("");
let clockTimer = null;

const currentIndex = computed(() =>
  Math.max(
    0,
    paper.value?.questions.findIndex(
      (item) => item.id === currentQuestionId.value,
    ) ?? 0,
  ),
);
const currentQuestion = computed(
  () => paper.value?.questions[currentIndex.value] || null,
);

/**
 * 状态辅助的上下文 key：作答开始后带上 attemptId，
 * 使题卷报告能按精确 key 取回本次作答期间的状态观察（困惑峰值 ↔ 失分题）。
 * 注：旧代码绑定的 paper.summary.paperCode 字段不存在（实际叫 code），一并修复。
 */
const stateContextKey = computed(() => {
  const code = paper.value?.summary?.code || "";
  return attempt.value ? `${code}:attempt:${attempt.value.id}` : code;
});
const currentDuration = computed(
  () => durationByQuestion[currentQuestionId.value] || 0,
);
const answeredCount = computed(
  () =>
    paper.value?.questions.filter((item) => isAnswered(item.id)).length || 0,
);
const unansweredCount = computed(() =>
  Math.max(0, (paper.value?.questions.length || 0) - answeredCount.value),
);
const saveStateText = computed(
  () =>
    ({
      saving: "正在保存草稿…",
      saved: "草稿已自动保存",
      error: "草稿保存失败，请手动重试",
    })[saveState.value] || "修改后将自动保存草稿",
);
const runningCode = computed(() => runningQuestionId.value === currentQuestionId.value);
const currentSandboxReport = computed(() => sandboxReports[currentQuestionId.value] || null);
const sandboxReportText = computed(() => {
  const report = currentSandboxReport.value;
  if (!report) return "";
  const parts = [`状态：${report.status}`, `耗时：${report.execution_time_ms || 0} ms`];
  if (report.output) parts.push(`输出：\n${report.output}`);
  if (report.error) parts.push(`错误：\n${report.error}`);
  return parts.join("\n");
});

const tutorContext = computed(() => {
  const question = currentQuestion.value;
  if (!question) return "";
  const options =
    question.options
      ?.map((option) => `${option.key}. ${option.text}`)
      .join("\n") || "";
  const review = revealedAnswers[question.id];
  return [
    `题卷：${paper.value.summary.title}`,
    `当前题目：${question.prompt}`,
    options ? `选项：\n${options}` : "",
    `我的答案：${answers[question.id] || "尚未作答"}`,
    `当前题用时：${formatDuration(durationByQuestion[question.id] || 0)}`,
    review
      ? `已提前查看标准答案：${review.standardAnswer}`
      : "尚未查看标准答案",
  ]
    .filter(Boolean)
    .join("\n\n");
});

function isAnswered(questionId) {
  return Boolean(String(answers[questionId] || "").trim());
}

function questionTypeLabel(type) {
  return (
    {
      SINGLE_CHOICE: "单项选择",
      CALCULATION: "计算推导",
      SHORT_ANSWER: "概念辨析",
      CODE_READING: "代码阅读",
      CODE_DEBUGGING: "代码纠错",
      MULTI_STEP: "多步骤综合",
    }[type] || type
  );
}

function formatDuration(seconds) {
  const value = Math.max(0, Number(seconds) || 0);
  const minutes = Math.floor(value / 60);
  const rest = value % 60;
  return `${String(minutes).padStart(2, "0")}:${String(rest).padStart(2, "0")}`;
}

function scheduleSave(questionId) {
  saveState.value = "saving";
  if (saveTimers.has(questionId))
    window.clearTimeout(saveTimers.get(questionId));
  saveTimers.set(
    questionId,
    window.setTimeout(() => saveAnswer(questionId), 850),
  );
}

async function saveAnswer(questionId) {
  if (!attempt.value?.id || !questionId) return;
  if (saveTimers.has(questionId)) {
    window.clearTimeout(saveTimers.get(questionId));
    saveTimers.delete(questionId);
  }
  saveState.value = "saving";
  try {
    await saveFixedExamAnswer(attempt.value.id, questionId, {
      userAnswer: answers[questionId] || "",
      durationSeconds: durationByQuestion[questionId] || 0,
    });
    saveState.value = "saved";
  } catch (saveError) {
    saveState.value = "error";
    throw saveError;
  }
}

async function saveCurrentAnswer() {
  try {
    await saveAnswer(currentQuestionId.value);
    toastSuccess("当前答案已保存");
  } catch (saveError) {
    toastError(saveError?.response?.data?.message || "答案保存失败");
  }
}

async function goToQuestion(questionId) {
  const previous = currentQuestionId.value;
  if (previous && previous !== questionId) {
    await saveAnswer(previous).catch(() => null);
  }
  currentQuestionId.value = questionId;
}

function moveQuestion(offset) {
  const target = paper.value?.questions[currentIndex.value + offset];
  if (target) void goToQuestion(target.id);
}

async function revealCurrentAnswer() {
  const questionId = currentQuestionId.value;
  if (!questionId || revealedAnswers[questionId]) return;
  try {
    await saveAnswer(questionId);
    revealedAnswers[questionId] = await revealFixedExamAnswer(
      attempt.value.id,
      questionId,
    );
  } catch (revealError) {
    toastError(revealError?.response?.data?.message || "答案暂时无法显示");
  }
}

function askTutor(prompt) {
  tutorQuestion.value = prompt;
  tutorVisible.value = true;
  tutorKey.value += 1;
}

async function runCurrentCode() {
  const question = currentQuestion.value;
  const questionId = question?.id;
  if (!questionId || runningQuestionId.value) return;
  const source = String(answers[questionId] || "").trim();
  if (!source) {
    toastError("请先填写可执行的 Python 代码。");
    return;
  }
  runningQuestionId.value = questionId;
  try {
    const executableCases = (question.testCases || []).filter((item) => String(item.assertion || "").trim());
    const harness = executableCases.length
      ? `${source}\n\n# 固定题卷自动测试\n${executableCases.map((item, index) => `assert ${item.assertion}, ${JSON.stringify(`用例 ${index + 1} 未通过`)}`).join("\n")}\nprint(${JSON.stringify(`通过 ${executableCases.length}/${executableCases.length} 个自动测试`)})`
      : source;
    sandboxReports[questionId] = await runPythonInBrowser(harness);
  } finally {
    runningQuestionId.value = "";
  }
}

async function submitExam() {
  if (submitting.value) return;
  if (
    unansweredCount.value &&
    !window.confirm(
      `还有 ${unansweredCount.value} 题未作答，确定提交整套题卷吗？`,
    )
  )
    return;
  submitting.value = true;
  try {
    await Promise.all(
      paper.value.questions.map((question) => saveAnswer(question.id)),
    );
    await submitFixedExamAttempt(attempt.value.id);
    attempt.value.status = "SUBMITTED";
    await router.push({
      name: "fixed-exam-report",
      params: { attemptId: String(attempt.value.id) },
    });
  } catch (submitError) {
    toastError(
      submitError?.response?.data?.message ||
        submitError?.message ||
        "题卷提交失败",
    );
  } finally {
    submitting.value = false;
  }
}

async function bootstrap() {
  loading.value = true;
  error.value = "";
  try {
    await learningSession.ensureCurrentSession("固定综合题卷");
    const paperCode = String(route.params.paperCode || "");
    const [paperResult, attemptResult] = await Promise.all([
      getFixedExamPaper(paperCode),
      startFixedExamAttempt(paperCode, learningSession.currentSessionId),
    ]);
    paper.value = paperResult;
    attempt.value = attemptResult;
    if (attemptResult.status === "SUBMITTED") {
      await router.replace({
        name: "fixed-exam-report",
        params: { attemptId: String(attemptResult.id) },
      });
      return;
    }
    for (const question of paperResult.questions) {
      const draft = attemptResult.answers?.find(
        (item) => item.questionId === question.id,
      );
      answers[question.id] = draft?.userAnswer || "";
      durationByQuestion[question.id] = draft?.durationSeconds || 0;
      if (draft?.viewedAnswerBeforeSubmit) {
        revealedAnswers[question.id] = await revealFixedExamAnswer(
          attemptResult.id,
          question.id,
        );
      }
    }
    totalSeconds.value = attemptResult.totalDurationSeconds || 0;
    currentQuestionId.value = paperResult.questions[0]?.id || "";
    clockTimer = window.setInterval(() => {
      totalSeconds.value += 1;
      if (currentQuestionId.value)
        durationByQuestion[currentQuestionId.value] =
          (durationByQuestion[currentQuestionId.value] || 0) + 1;
    }, 1000);
  } catch (loadError) {
    error.value =
      loadError?.response?.data?.message ||
      loadError?.message ||
      "请稍后重试。";
  } finally {
    loading.value = false;
  }
}

onMounted(bootstrap);
onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer);
  for (const timer of saveTimers.values()) window.clearTimeout(timer);
  if (
    attempt.value?.id &&
    attempt.value.status === "IN_PROGRESS" &&
    currentQuestionId.value
  )
    void saveAnswer(currentQuestionId.value).catch(() => null);
});
</script>

<style scoped>
.exam-page {
  max-width: 1440px;
}

.exam-state,
.exam-header,
.question-navigation,
.question-workspace {
  padding: var(--vt-space-5);
}

.exam-state {
  display: grid;
  justify-items: start;
  gap: var(--vt-space-3);
}

.exam-header,
.exam-timers,
.question-heading,
.question-actions,
.exam-submit {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
}

.exam-header h1 {
  margin: var(--vt-space-1) 0;
  color: var(--vt-text-primary);
  font-size: clamp(1.5rem, 3vw, 2.2rem);
}

.exam-header p {
  margin: 0;
  color: var(--vt-text-secondary);
}

.exam-timers {
  flex-wrap: wrap;
  justify-content: flex-end;
}

.exam-timers span {
  display: grid;
  gap: 2px;
  min-width: 100px;
  padding: var(--vt-space-2) var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.exam-timers strong {
  color: var(--vt-text-primary);
  font-size: var(--vt-text-lg);
}

.exam-layout {
  display: grid;
  grid-template-columns: 210px minmax(0, 1fr) 340px;
  align-items: start;
  gap: var(--vt-space-4);
  margin-top: var(--vt-space-4);
}

.question-navigation,
.tutor-column {
  position: sticky;
  top: 92px;
}

.question-navigation {
  display: grid;
  gap: var(--vt-space-4);
}

.question-navigation header {
  display: grid;
  gap: 2px;
}

.question-navigation header span,
.save-state {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.question-numbers {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-2);
}

.question-numbers button {
  display: grid;
  gap: 2px;
  padding: var(--vt-space-2);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-primary);
  color: var(--vt-text-primary);
  cursor: pointer;
}

.question-numbers button.answered {
  border-color: rgba(22, 163, 74, 0.42);
}

.question-numbers button.current {
  border-color: var(--vt-accent-teal);
  box-shadow: 0 0 0 2px rgba(13, 148, 136, 0.12);
}

.question-numbers button.revealed::after {
  content: "已看答案";
  color: #b45309;
  font-size: 9px;
}

.question-numbers small {
  color: var(--vt-text-tertiary);
}

.question-navigation dl {
  display: grid;
  gap: var(--vt-space-2);
  margin: 0;
}

.question-navigation dl div {
  display: flex;
  justify-content: space-between;
}

.question-navigation dt {
  color: var(--vt-text-tertiary);
}

.question-navigation dd {
  margin: 0;
  font-weight: var(--vt-font-semibold);
}

.save-state {
  margin: 0;
  line-height: 1.5;
}

.question-workspace {
  display: grid;
  gap: var(--vt-space-5);
  min-width: 0;
}

.question-heading {
  align-items: flex-start;
}

.question-heading > div:first-child {
  display: grid;
  gap: 2px;
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.question-index {
  color: var(--vt-text-primary);
  font-size: var(--vt-text-lg);
  font-weight: var(--vt-font-bold);
}

.knowledge-tags {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--vt-space-1);
}

.knowledge-tags span {
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(13, 148, 136, 0.08);
  color: var(--vt-accent-teal-dark);
  font-size: var(--vt-text-xs);
}

.question-prompt {
  font-size: 1.05rem;
  line-height: 1.8;
}

.sub-questions {
  display: grid;
  gap: var(--vt-space-2);
  margin: 0;
  padding: var(--vt-space-3) var(--vt-space-3) var(--vt-space-3) 2rem;
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
  color: var(--vt-text-secondary);
}

.choice-list {
  display: grid;
  gap: var(--vt-space-2);
  margin: 0;
  padding: 0;
  border: 0;
}

.choice-list legend,
.answer-editor > span {
  margin-bottom: var(--vt-space-2);
  color: var(--vt-text-secondary);
  font-weight: var(--vt-font-semibold);
}

.choice-list label {
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr);
  align-items: start;
  gap: var(--vt-space-3);
  padding: var(--vt-space-3);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  cursor: pointer;
}

.answer-editor {
  display: grid;
}

.answer-editor textarea {
  width: 100%;
  min-height: 260px;
  padding: var(--vt-space-4);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-primary);
  color: var(--vt-text-primary);
  font: inherit;
  line-height: 1.75;
  resize: vertical;
}

.answer-editor textarea.code-answer {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  tab-size: 4;
}

.revealed-review {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-4);
  border: 1px solid rgba(245, 158, 11, 0.3);
  border-radius: var(--vt-radius-lg);
  background: rgba(245, 158, 11, 0.05);
}

.code-validation { display: grid; gap: .8rem; padding: 1rem; border: 1px solid var(--vt-border-light); border-radius: var(--vt-radius-lg); background: var(--vt-bg-secondary); }
.code-validation__heading { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
.code-validation__heading p, .verification-criteria p { margin: .25rem 0 0; color: var(--vt-text-secondary); font-size: .78rem; line-height: 1.5; }
.sandbox-report { max-height: 280px; overflow: auto; margin: 0; padding: .8rem; border-radius: .6rem; background: #0f172a; color: #e2e8f0; white-space: pre-wrap; overflow-wrap: anywhere; }
.sandbox-report.status-success { border-left: 4px solid #22c55e; }
.sandbox-report.status-error, .sandbox-report.status-blocked, .sandbox-report.status-timeout { border-left: 4px solid #ef4444; }
.verification-criteria { display: grid; gap: .55rem; }
.verification-criteria ul { display: grid; gap: .45rem; margin: 0; padding: 0; list-style: none; }
.verification-criteria li { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: .2rem .55rem; padding: .55rem; border-radius: .5rem; background: var(--vt-bg-primary); font-size: .78rem; }
.verification-criteria li > span { color: #b45309; font-weight: 700; }
.verification-criteria li small { grid-column: 2; color: var(--vt-text-secondary); }

.revealed-review h2,
.revealed-review p {
  margin: 0;
}

.answer-warning {
  color: #b45309;
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
}

.question-actions {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.exam-submit {
  padding-top: var(--vt-space-4);
  border-top: 1px solid var(--vt-border-light);
}

.exam-submit div {
  display: grid;
  gap: 2px;
}

.exam-submit span {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.tutor-column {
  display: grid;
}

.reopen-tutor {
  width: 100%;
}

@media (max-width: 1180px) {
  .exam-layout {
    grid-template-columns: 190px minmax(0, 1fr);
  }

  .tutor-column {
    position: static;
    grid-column: 1 / -1;
  }
}

@media (max-width: 760px) {
  .exam-header,
  .question-heading,
  .exam-submit {
    align-items: stretch;
    flex-direction: column;
  }

  .exam-layout {
    grid-template-columns: 1fr;
  }

  .question-navigation {
    position: static;
  }

  .question-numbers {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .knowledge-tags {
    justify-content: flex-start;
  }
  .code-validation__heading { align-items: stretch; flex-direction: column; }
}
</style>
