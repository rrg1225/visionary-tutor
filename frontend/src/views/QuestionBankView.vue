<template>
  <section class="question-bank vt-section vt-container">
    <header class="question-bank-header">
      <div>
        <span class="vt-eyebrow">题库 · 错题本 · 复习计划</span>
        <h1 class="vt-title">用练习确认真正掌握了什么</h1>
        <p class="vt-text-muted">
          题库用于日常练习；知识测评负责阶段诊断。提交答案后会显示解析，错题自动进入复习计划。
        </p>
      </div>
      <div class="question-bank-actions">
        <RouterLink class="vt-btn vt-btn-outline" to="/assessment-fill">
          进入知识测评
        </RouterLink>
        <RouterLink class="vt-btn vt-btn-primary" to="/questions/personalized">
          生成专项练习
        </RouterLink>
      </div>
    </header>

    <nav class="bank-navigation vt-card" aria-label="题库分类">
      <strong>题库分类</strong>
      <a href="#fixed-papers">固定试卷</a>
      <a href="#due-reviews">今日复习</a>
      <a href="#ai-practice">AI 专项练习</a>
      <a href="#wrong-book">错题与复习统计</a>
      <RouterLink to="/questions/personalized">生成新练习</RouterLink>
    </nav>

    <section
      id="fixed-papers"
      class="bank-section"
      aria-labelledby="fixed-paper-title"
    >
      <header class="section-head">
        <div>
          <span class="vt-eyebrow">平台精选综合题卷</span>
          <h2 id="fixed-paper-title">固定题目、固定答案、提交后生成题卷报告</h2>
        </div>
        <span>{{ fixedPapers.length }} 套 · 每套 8 题</span>
      </header>

      <p class="review-disclosure vt-card" role="status">
        当前题卷已通过结构完整性与自动一致性校验，状态为“等待团队人工签字”。在团队完成最终校审前，页面会持续显示该标识，不把自动校验冒充人工审核。
      </p>

      <div v-if="fixedPaperLoading" class="empty-state vt-card">
        正在加载固定题卷…
      </div>
      <div v-else-if="fixedPaperError" class="empty-state vt-card" role="alert">
        <strong>固定题卷暂时无法加载</strong>
        <p>{{ fixedPaperError }}</p>
      </div>
      <div v-else class="fixed-paper-grid">
        <article
          v-for="paper in fixedPapers"
          :key="paper.code"
          class="fixed-paper-card vt-card"
        >
          <header>
            <span class="paper-topic">{{ paper.topic }}</span>
            <span class="paper-review">{{
              reviewStatusLabel(paper.reviewStatus)
            }}</span>
          </header>
          <h3>{{ paper.title }}</h3>
          <p>{{ paper.description }}</p>
          <dl>
            <div>
              <dt>题量</dt>
              <dd>{{ paper.questionCount }} 题</dd>
            </div>
            <div>
              <dt>时长</dt>
              <dd>{{ paper.durationMinutes }} 分钟</dd>
            </div>
            <div>
              <dt>难度</dt>
              <dd>{{ difficultyLabel(paper.difficulty) }}</dd>
            </div>
            <div>
              <dt>总分</dt>
              <dd>{{ paper.maxScore }} 分</dd>
            </div>
          </dl>
          <RouterLink
            class="vt-btn vt-btn-primary vt-btn-sm"
            :to="{ name: 'fixed-exam', params: { paperCode: paper.code } }"
          >
            进入校审版题卷
          </RouterLink>
        </article>
      </div>
    </section>

    <section id="wrong-book" class="review-summary vt-card">
      <div>
        <strong>{{ wrongBook.length }}</strong>
        <span>错题记录</span>
      </div>
      <div>
        <strong>{{ dueReviews.length }}</strong>
        <span>当前待复习</span>
      </div>
      <div>
        <strong>{{ quizResources.length }}</strong>
        <span>可练习题库</span>
      </div>
    </section>

    <section id="due-reviews" class="bank-section">
      <header class="section-head">
        <div>
          <span class="vt-eyebrow">今日复习</span>
          <h2>到期错题</h2>
        </div>
        <span>{{
          dueReviews.length ? "答对后会移出错题本" : "当前没有到期任务"
        }}</span>
      </header>

      <div v-if="dueReviews.length" class="wrong-grid">
        <article
          v-for="item in dueReviews"
          :key="item.id"
          class="wrong-card vt-card"
        >
          <span class="wrong-concept">{{ item.concept || "综合练习" }}</span>
          <span v-if="sourceLabel(item.sourceType)" class="wrong-source">
            {{ sourceLabel(item.sourceType) }}
          </span>
          <h3>{{ item.prompt }}</h3>
          <p>
            <strong>上次回答：</strong
            >{{ item.userAnswer || "未作答/直接查看答案" }}
          </p>
          <details>
            <summary>查看答案与解析</summary>
            <p>
              <strong>参考答案：</strong
              >{{ item.correctAnswer || "暂无结构化答案" }}
            </p>
            <MarkdownPanel
              v-if="item.explanation"
              :content="item.explanation"
            />
          </details>
          <div class="wrong-actions">
            <button
              type="button"
              class="vt-btn vt-btn-outline vt-btn-sm"
              @click="openWrongTutor(item)"
            >
              问 AI 老师
            </button>
            <button
              type="button"
              class="vt-btn vt-btn-ghost vt-btn-sm"
              @click="markReviewed(item, false)"
            >
              仍需复习
            </button>
            <button
              type="button"
              class="vt-btn vt-btn-primary vt-btn-sm"
              @click="markReviewed(item, true)"
            >
              我已掌握
            </button>
          </div>
        </article>
      </div>
      <div v-else class="empty-state vt-card">
        <strong>今天没有到期错题</strong>
        <p>完成题库练习后，答错或直接查看答案的题目会自动加入复习计划。</p>
      </div>
    </section>

    <ContextualTutorPanel
      v-if="selectedWrongItem"
      class="wrong-tutor"
      title="针对这道错题提问"
      :context="wrongTutorContext"
      suggested-question="先指出我思路里的第一处问题，不要直接给完整答案。"
      :learning-session-id="learningSession.currentSessionId"
      context-type="WRONG_BOOK"
      :context-key="`wrong-book:${selectedWrongItem.id}`"
      :context-title="selectedWrongItem.concept || '错题复习'"
      answer-mode="ANSWER_REVEALED"
      @close="selectedWrongItem = null"
    />

    <section id="ai-practice" class="bank-section">
      <header class="section-head">
        <div>
          <span class="vt-eyebrow">专项练习</span>
          <h2>我的题库</h2>
        </div>
        <button
          v-if="quizResources.length"
          type="button"
          class="vt-btn vt-btn-outline vt-btn-sm"
          :aria-expanded="quizHistoryOpen"
          @click="quizHistoryOpen = !quizHistoryOpen"
        >
          {{
            quizHistoryOpen
              ? "收起练习历史"
              : `选择练习历史（${quizResources.length}）`
          }}
        </button>
      </header>

      <div
        v-if="quizResources.length"
        class="quiz-history-layout"
        :class="{ open: quizHistoryOpen }"
      >
        <aside v-if="quizHistoryOpen" class="quiz-history vt-card">
          <label class="vt-label" for="quiz-history-search">搜索练习历史</label>
          <input
            id="quiz-history-search"
            v-model.trim="quizHistoryQuery"
            class="vt-input"
            type="search"
            placeholder="搜索主题或标题"
          />
          <button
            v-for="resource in filteredQuizHistory"
            :key="`quiz-history-${resource.id || resource.runId}`"
            type="button"
            class="quiz-history-item"
            :class="{ active: quizKey(resource) === selectedQuizKey }"
            @click="selectedQuizKey = quizKey(resource)"
          >
            <strong>{{
              resource.sessionTopic || resource.title || "专项练习"
            }}</strong>
            <small>{{ resource.title || "分层练习题库" }}</small>
          </button>
          <p v-if="!filteredQuizHistory.length" class="vt-text-muted">
            没有匹配的练习记录。
          </p>
        </aside>

        <article v-if="selectedQuizResource" class="quiz-resource vt-card">
          <header>
            <div>
              <span>{{
                selectedQuizResource.sessionTopic || "个性化练习"
              }}</span>
              <h3>{{ selectedQuizResource.title || "分层练习题库" }}</h3>
            </div>
            <span v-if="selectedQuizResource.isShowcase" class="showcase-badge"
              >示例</span
            >
          </header>
          <QuizResourceCard
            :content="selectedQuizResource.content"
            :user-id="Number(authStore.currentUserId)"
            :learning-session-id="
              selectedQuizResource.learningSessionId ||
              learningSession.currentSessionId
            "
            :on-submit="submitQuizResult"
            @submitted="refreshWrongBook"
          />
        </article>
        <div v-else class="empty-state vt-card">
          <strong>选择一条练习历史后再查看题目</strong>
          <p>
            历史题目默认不会全部展开，避免页面过长。点击“选择练习历史”打开需要继续的那一组。
          </p>
        </div>
      </div>
      <div v-else class="empty-state vt-card">
        <strong>还没有个人题库</strong>
        <p>专项练习有独立生成页，不会同时生成讲义、动画、代码或 PPT。</p>
        <RouterLink
          class="vt-btn vt-btn-primary vt-btn-sm"
          to="/questions/personalized"
        >
          生成第一组专项练习
        </RouterLink>
      </div>
    </section>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import ContextualTutorPanel from "../components/ContextualTutorPanel.vue";
import MarkdownPanel from "../components/common/MarkdownPanel.vue";
import QuizResourceCard from "../components/resource/cards/QuizResourceCard.vue";
import { listFixedExamPapers } from "../api/fixedExams";
import {
  fetchDueReviews,
  fetchWrongBook,
  reviewQuestionAttempt,
} from "../api/questionBank";
import { formatApiErrorMessage, submitQuizResult } from "../api/resources";
import { useResourceLibrary } from "../composables/useResourceLibrary";
import { useAuthStore } from "../stores/authStore";
import { useLearningSessionStore } from "../stores/learningSession";

const authStore = useAuthStore();
const learningSession = useLearningSessionStore();
const wrongBook = ref([]);
const dueReviews = ref([]);
const selectedWrongItem = ref(null);
const selectedQuizKey = ref("");
const quizHistoryOpen = ref(false);
const quizHistoryQuery = ref("");
const fixedPapers = ref([]);
const fixedPaperLoading = ref(true);
const fixedPaperError = ref("");

const { libraryResources, loadLibrary } = useResourceLibrary();

function sourceLabel(sourceType) {
  return (
    {
      FIXED_EXAM: "精选题卷",
      CONTENT_SELF_TEST: "教材自考",
      AI_PRACTICE: "AI 练习",
    }[sourceType] || ""
  );
}

const quizResources = computed(() =>
  libraryResources.value.filter((item) => item.artifactType === "QUIZ"),
);
const filteredQuizHistory = computed(() => {
  const query = quizHistoryQuery.value.toLowerCase();
  if (!query) return quizResources.value;
  return quizResources.value.filter((item) =>
    `${item.sessionTopic || ""} ${item.title || ""}`
      .toLowerCase()
      .includes(query),
  );
});
const selectedQuizResource = computed(
  () =>
    quizResources.value.find(
      (item) => quizKey(item) === selectedQuizKey.value,
    ) || null,
);

function quizKey(resource) {
  return String(
    resource.id ?? resource.runId ?? `${resource.learningSessionId}-QUIZ`,
  );
}
const wrongTutorContext = computed(() => {
  const item = selectedWrongItem.value;
  if (!item) return "";
  return [
    `题目：${item.prompt}`,
    `我上次的回答：${item.userAnswer || "未作答/直接查看答案"}`,
    `参考答案：${item.correctAnswer || "暂无"}`,
    item.explanation ? `已有解析：${item.explanation}` : "",
  ]
    .filter(Boolean)
    .join("\n\n");
});

async function refreshWrongBook() {
  const [all, due] = await Promise.all([
    fetchWrongBook().catch(() => []),
    fetchDueReviews().catch(() => []),
  ]);
  wrongBook.value = Array.isArray(all) ? all : [];
  dueReviews.value = Array.isArray(due) ? due : [];
}

async function loadFixedPapers() {
  fixedPaperLoading.value = true;
  fixedPaperError.value = "";
  try {
    const result = await listFixedExamPapers({ silent: true });
    fixedPapers.value = Array.isArray(result) ? result : [];
  } catch (error) {
    fixedPapers.value = [];
    fixedPaperError.value = formatApiErrorMessage(
      error,
      "固定题卷暂时无法加载，请稍后重试。",
    );
  } finally {
    fixedPaperLoading.value = false;
  }
}

function difficultyLabel(value) {
  return value === "ADVANCED" ? "高难度" : value || "综合";
}

function reviewStatusLabel(value) {
  return value === "TEAM_REVIEW_REQUIRED" ? "待团队签字" : "已审核";
}

function openWrongTutor(item) {
  selectedWrongItem.value = item;
}

async function markReviewed(item, correct) {
  await reviewQuestionAttempt(item.id, correct);
  if (selectedWrongItem.value?.id === item.id && correct)
    selectedWrongItem.value = null;
  await refreshWrongBook();
}

onMounted(async () => {
  await learningSession.ensureCurrentSession("题库与错题复习");
  await Promise.all([
    loadLibrary(Number(authStore.currentUserId)),
    refreshWrongBook(),
    loadFixedPapers(),
  ]);
});
</script>

<style scoped>
.question-bank,
.bank-section,
.quiz-list {
  display: grid;
  gap: var(--vt-space-5);
}

.question-bank {
  max-width: 1340px;
  grid-template-columns: 190px minmax(0, 1fr);
  align-items: start;
}

.question-bank-header {
  grid-column: 1 / -1;
}
.question-bank > :not(.question-bank-header):not(.bank-navigation) {
  grid-column: 2;
}
.bank-navigation {
  grid-column: 1;
  grid-row: 2 / span 20;
  position: sticky;
  top: 92px;
  padding: 1rem;
  display: grid;
  gap: 0.35rem;
}
.bank-navigation strong {
  margin-bottom: 0.35rem;
}
.bank-navigation a {
  padding: 0.55rem 0.65rem;
  border-radius: 0.55rem;
  color: var(--vt-text-secondary);
  font-size: 0.84rem;
  text-decoration: none;
}
.bank-navigation a:hover {
  background: rgba(13, 148, 136, 0.08);
  color: var(--vt-accent-teal-dark);
}

.question-bank-header,
.section-head,
.quiz-resource > header,
.wrong-actions,
.question-bank-actions {
  display: flex;
  align-items: center;
}

.question-bank-header,
.section-head,
.quiz-resource > header {
  justify-content: space-between;
  gap: var(--vt-space-4);
}

.question-bank-header p {
  max-width: 760px;
  line-height: 1.7;
}

.review-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--vt-space-3);
  padding: var(--vt-space-5);
}

.review-summary div {
  display: grid;
  gap: var(--vt-space-1);
  text-align: center;
}

.review-summary strong {
  color: var(--vt-text-primary);
  font-size: 1.8rem;
}

.review-summary span,
.section-head > span,
.quiz-resource > header span {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.section-head h2,
.wrong-card h3,
.quiz-resource h3 {
  margin: 0;
  color: var(--vt-text-primary);
}

.wrong-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-4);
}

.fixed-paper-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-4);
}

.review-disclosure {
  margin: 0;
  padding: var(--vt-space-3) var(--vt-space-4);
  border-color: rgba(245, 158, 11, 0.32);
  background: rgba(245, 158, 11, 0.06);
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
  line-height: 1.6;
}

.fixed-paper-card {
  display: grid;
  justify-items: start;
  gap: var(--vt-space-3);
  padding: var(--vt-space-5);
}

.fixed-paper-card > header {
  width: 100%;
  display: flex;
  justify-content: space-between;
  gap: var(--vt-space-3);
}

.fixed-paper-card h3,
.fixed-paper-card p {
  margin: 0;
}

.fixed-paper-card p {
  color: var(--vt-text-secondary);
  line-height: 1.6;
}

.paper-topic,
.paper-review {
  font-size: var(--vt-text-xs);
}

.paper-topic {
  color: var(--vt-accent-teal-dark);
}

.paper-review {
  color: #b45309;
}

.fixed-paper-card dl {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--vt-space-2);
  margin: 0;
}

.fixed-paper-card dl div {
  display: grid;
  gap: 2px;
  padding: var(--vt-space-2);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
  text-align: center;
}

.fixed-paper-card dt {
  color: var(--vt-text-tertiary);
  font-size: 10px;
}

.fixed-paper-card dd {
  margin: 0;
  color: var(--vt-text-primary);
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
}

.wrong-card,
.quiz-resource,
.empty-state {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-5);
}

.wrong-card h3,
.quiz-resource h3 {
  font-size: 1rem;
  line-height: 1.55;
}

.wrong-card p,
.empty-state p {
  margin: 0;
  color: var(--vt-text-secondary);
  line-height: 1.6;
}

.wrong-concept,
.showcase-badge {
  width: fit-content;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(245, 158, 11, 0.1);
  color: #b45309;
  font-size: var(--vt-text-xs);
}

.wrong-source {
  width: fit-content;
  padding: 4px 8px;
  border-radius: 999px;
  background: rgba(13, 148, 136, 0.1);
  color: var(--vt-accent-teal-dark, #0f766e);
  font-size: var(--vt-text-xs);
}

.wrong-actions {
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}

.question-bank-actions {
  justify-content: flex-end;
  flex-wrap: wrap;
}

.wrong-tutor {
  position: sticky;
  bottom: var(--vt-space-4);
  z-index: 2;
}

.quiz-resource {
  min-width: 0;
}

.quiz-history-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: start;
  gap: var(--vt-space-4);
}

.quiz-history-layout.open {
  grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
}

.quiz-history {
  position: sticky;
  top: 92px;
  display: grid;
  gap: var(--vt-space-2);
  max-height: calc(100vh - 112px);
  padding: var(--vt-space-4);
  overflow: auto;
}

.quiz-history-item {
  display: grid;
  gap: 3px;
  width: 100%;
  padding: var(--vt-space-3);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
  color: inherit;
  text-align: left;
  cursor: pointer;
}

.quiz-history-item.active {
  border-color: rgba(13, 148, 136, 0.55);
  background: rgba(13, 148, 136, 0.09);
}

.quiz-history-item strong,
.quiz-history-item small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.quiz-history-item small {
  color: var(--vt-text-tertiary);
}

.empty-state {
  justify-items: start;
}

@media (max-width: 760px) {
  .question-bank {
    grid-template-columns: 1fr;
  }
  .question-bank > :not(.question-bank-header):not(.bank-navigation),
  .question-bank-header,
  .bank-navigation {
    grid-column: 1;
  }
  .bank-navigation {
    grid-row: auto;
    position: static;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .bank-navigation strong {
    grid-column: 1 / -1;
  }
  .question-bank-header,
  .section-head {
    align-items: stretch;
    flex-direction: column;
  }

  .question-bank-actions {
    justify-content: flex-start;
  }

  .review-summary,
  .wrong-grid,
  .fixed-paper-grid {
    grid-template-columns: 1fr;
  }

  .fixed-paper-card dl {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  #fixed-papers {
    margin-top: var(--vt-space-4);
  }

  .quiz-history-layout.open {
    grid-template-columns: 1fr;
  }

  .quiz-history {
    position: static;
    max-height: min(50vh, 460px);
  }
}
</style>
