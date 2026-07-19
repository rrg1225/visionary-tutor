<template>
  <section class="report-page vt-container vt-section">
    <div v-if="loading" class="vt-card report-state">
      正在生成并核对题卷报告…
    </div>
    <div v-else-if="error" class="vt-card report-state" role="alert">
      <strong>报告暂时无法打开</strong>
      <p>{{ error }}</p>
      <RouterLink class="vt-btn vt-btn-outline" to="/questions"
        >返回题库与测评</RouterLink
      >
    </div>

    <template v-else-if="report">
      <header class="report-hero vt-card">
        <div>
          <span class="vt-eyebrow">固定题卷 · 提交后报告</span>
          <h1>{{ report.paperTitle }}</h1>
          <p>
            本报告按题卷预设评分点自动判定，用于学习诊断；主观题与代码题的自动评分结果仍应结合标准答案复核。
          </p>
        </div>
        <div class="score-block" aria-label="题卷总分">
          <strong>{{ displayScore(report.totalScore) }}</strong>
          <span>/ {{ displayScore(report.maxScore) }} 分</span>
        </div>
      </header>

      <section class="report-metrics" aria-label="答题摘要">
        <article class="vt-card">
          <span>整题正确率</span>
          <strong>{{ report.accuracyPercent }}%</strong>
        </article>
        <article class="vt-card">
          <span>整卷用时</span>
          <strong>{{ formatDuration(report.totalDurationSeconds) }}</strong>
        </article>
        <article class="vt-card">
          <span>已答 / 未答</span>
          <strong
            >{{ report.answeredCount }} / {{ report.unansweredCount }}</strong
          >
        </article>
        <article class="vt-card">
          <span>提交前看过答案</span>
          <strong>{{ report.viewedAnswerQuestionIds.length }} 题</strong>
        </article>
      </section>

      <section
        v-if="report.viewedAnswerQuestionIds.length"
        class="integrity-note vt-card"
      >
        <strong>作答过程说明</strong>
        <p>
          第
          {{
            viewedQuestionOrders.join("、")
          }}
          题在提交前查看过标准答案。题目仍参与计分，但已单独标记，便于你正确理解本次成绩。
        </p>
      </section>

      <div class="report-layout">
        <main class="report-main">
          <section
            class="report-section vt-card"
            aria-labelledby="mastery-title"
          >
            <header class="section-heading">
              <div>
                <span class="vt-eyebrow">知识点掌握</span>
                <h2 id="mastery-title">得分拆解</h2>
              </div>
              <RouterLink
                class="vt-btn vt-btn-primary vt-btn-sm"
                :to="personalizedPracticeTarget"
              >
                针对薄弱项生成练习
              </RouterLink>
            </header>
            <div class="mastery-list">
              <article v-for="item in sortedMastery" :key="item.knowledgePoint">
                <header>
                  <strong>{{ item.knowledgePoint }}</strong>
                  <span
                    >{{ displayScore(item.earnedScore) }} /
                    {{ displayScore(item.maxScore) }} 分</span
                  >
                </header>
                <div class="mastery-track" aria-hidden="true">
                  <span :style="{ width: `${item.masteryPercent}%` }"></span>
                </div>
                <span>{{ item.masteryPercent }}% 掌握</span>
              </article>
            </div>
          </section>

          <section
            class="report-section vt-card"
            aria-labelledby="diagnosis-title"
          >
            <header class="section-heading">
              <div>
                <span class="vt-eyebrow">复盘建议</span>
                <h2 id="diagnosis-title">典型问题与回学入口</h2>
              </div>
            </header>
            <div class="diagnosis-grid">
              <div>
                <h3>本次典型问题</h3>
                <ul v-if="report.typicalErrors.length">
                  <li v-for="item in report.typicalErrors" :key="item">
                    {{ item }}
                  </li>
                </ul>
                <p v-else>
                  没有检测到典型错误，建议继续完成迁移练习确认稳定掌握。
                </p>
              </div>
              <div>
                <h3>建议回学内容</h3>
                <ul v-if="report.recommendedReviews.length">
                  <li v-for="item in report.recommendedReviews" :key="item">
                    {{ item }}
                  </li>
                </ul>
                <p v-else>本次没有额外回学建议。</p>
              </div>
            </div>
          </section>

          <section
            class="report-section vt-card"
            aria-labelledby="state-observation-title"
          >
            <header class="section-heading">
              <div>
                <span class="vt-eyebrow">学习状态观察</span>
                <h2 id="state-observation-title">本次作答期间的状态信号</h2>
              </div>
            </header>
            <p v-if="!latestStateReport" class="state-note">
              本报告不包含情绪或专注判断：本次作答未开启学习状态辅助。
              下次可在做题页顶部开启，本地识别、原始视频不上传。
            </p>
            <template v-else>
              <p class="state-note">
                {{ latestStateReport.headline }} ·
                有效样本 {{ latestStateReport.sampleCount }} 个 ·
                观察 {{ latestStateReport.durationSeconds }} 秒
              </p>
              <p v-if="!latestStateReport.sufficient" class="state-note">
                {{ latestStateReport.description }}
              </p>
              <div v-else-if="confusionHighlights.length" class="state-highlights">
                <p class="state-note">
                  以下题目在作答时观察到相对较高的视觉负荷信号（只是提示，不代表情绪判断）：
                </p>
                <ul>
                  <li v-for="item in confusionHighlights" :key="item.order">
                    <a :href="`#question-${item.order}`">第 {{ item.order }} 题</a>
                    · 信号均值 {{ item.averageScore }}/100（{{ item.sampleCount }} 个样本）
                    · {{ highlightNote(item) }}
                  </li>
                </ul>
              </div>
              <p v-else class="state-note">
                本次观察没有形成按题目的信号分布，仅有整体均值
                {{ latestStateReport.aggregateScore }}/100 供参考。
              </p>
            </template>
          </section>

          <section
            class="question-review-section"
            aria-labelledby="question-review-title"
          >
            <header class="section-heading review-title">
              <div>
                <span class="vt-eyebrow">逐题复盘</span>
                <h2 id="question-review-title">答案、得分点与解析</h2>
              </div>
              <span>共 {{ report.questions.length }} 题</span>
            </header>

            <article
              v-for="question in report.questions"
              :id="`question-${question.order}`"
              :key="question.questionId"
              class="question-review vt-card"
            >
              <header class="question-review-heading">
                <div>
                  <span class="question-number"
                    >第 {{ question.order }} 题</span
                  >
                  <span class="result-label" :class="resultClass(question)">
                    {{ resultLabel(question) }}
                  </span>
                  <span
                    v-if="question.viewedAnswerBeforeSubmit"
                    class="viewed-label"
                    >提交前看过答案</span
                  >
                </div>
                <strong
                  >{{ displayScore(question.score) }} /
                  {{ displayScore(question.maxScore) }} 分</strong
                >
              </header>

              <MarkdownPanel
                class="question-prompt"
                :content="question.prompt"
              />

              <div class="answer-comparison">
                <section>
                  <h3>你的答案</h3>
                  <pre>{{ question.userAnswer || "未作答" }}</pre>
                </section>
                <section>
                  <h3>标准答案</h3>
                  <pre>{{ question.standardAnswer }}</pre>
                </section>
              </div>

              <section class="scoring-points">
                <h3>评分点</h3>
                <ul>
                  <li v-for="point in question.scoringPoints" :key="point.id">
                    <span
                      :class="
                        point.achieved ? 'point-achieved' : 'point-missed'
                      "
                    >
                      {{ point.achieved ? "已命中" : "未命中" }}
                    </span>
                    <span>{{ point.description }}</span>
                    <strong>{{ displayScore(point.points) }} 分</strong>
                  </li>
                </ul>
                <p v-if="question.reviewRecommended" class="grading-review-note">
                  主观题先按公开评分点给出确定性分数；本题建议由教师或模型按完整推理复核，避免关键词规则遗漏等价表达。
                </p>
              </section>

              <details open>
                <summary>详细解析</summary>
                <MarkdownPanel :content="question.explanation" />
              </details>

              <div class="review-details">
                <section>
                  <h3>常见错误</h3>
                  <ul>
                    <li v-for="item in question.commonErrors" :key="item">
                      {{ item }}
                    </li>
                  </ul>
                </section>
                <section
                  v-if="Object.keys(question.distractorAnalysis || {}).length"
                >
                  <h3>选项分析</h3>
                  <ul>
                    <li
                      v-for="(value, key) in question.distractorAnalysis"
                      :key="key"
                    >
                      <strong>{{ key }}：</strong>{{ value }}
                    </li>
                  </ul>
                </section>
              </div>

              <details class="validation-details">
                <summary>依据与验证方式</summary>
                <p>
                  <strong>验证方式：</strong>{{ question.validationMethod }}
                </p>
                <ul v-if="question.testCases.length">
                  <li
                    v-for="testCase in question.testCases"
                    :key="testCase.input"
                  >
                    输入：{{ testCase.input }}；预期：{{ testCase.expected }}
                  </li>
                </ul>
                <ul class="source-list">
                  <li v-for="source in question.sources" :key="source.url">
                    <a
                      :href="source.url"
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      {{ source.title }}
                    </a>
                  </li>
                </ul>
              </details>

              <footer class="question-review-actions">
                <RouterLink
                  class="vt-btn vt-btn-outline vt-btn-sm"
                  :to="practiceTargetFor(question)"
                >
                  练习这个知识点
                </RouterLink>
                <RouterLink
                  class="vt-btn vt-btn-ghost vt-btn-sm"
                  to="/questions"
                >
                  返回错题本
                </RouterLink>
                <RouterLink class="vt-btn vt-btn-ghost vt-btn-sm" to="/library">
                  回到来源内容
                </RouterLink>
                <RouterLink
                  class="vt-btn vt-btn-ghost vt-btn-sm"
                  :to="{ path: '/learn', query: { prompt: `请讲解这道错题：${question.prompt}` } }"
                >
                  问 AI 老师
                </RouterLink>
              </footer>
            </article>
          </section>
        </main>

        <aside class="report-aside vt-card">
          <strong>逐题导航</strong>
          <nav aria-label="报告题号导航">
            <a
              v-for="question in report.questions"
              :key="question.questionId"
              :href="`#question-${question.order}`"
              :class="resultClass(question)"
            >
              <span>第 {{ question.order }} 题</span>
              <small>{{ resultLabel(question) }}</small>
            </a>
          </nav>
          <RouterLink class="vt-btn vt-btn-primary" to="/questions"
            >回到题库与测评</RouterLink
          >
        </aside>
      </div>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { RouterLink, useRoute } from "vue-router";
import MarkdownPanel from "../components/common/MarkdownPanel.vue";
import { getFixedExamReport } from "../api/fixedExams";
import { listLearningStateReports } from "../api/learningState";

const route = useRoute();
const report = ref(null);
const loading = ref(true);
const error = ref("");
const stateReports = ref([]);

/** 本次作答期间的状态观察（按 paperCode:attempt:attemptId 精确匹配）。 */
const latestStateReport = computed(() => stateReports.value[0] || null);

/** 困惑峰值 ↔ 失分题 交叉：把状态报告的按题标记映射到本卷题目。 */
const confusionHighlights = computed(() => {
  const markers = latestStateReport.value?.sufficient
    ? latestStateReport.value.markers || []
    : [];
  if (!markers.length || !report.value) return [];
  const byQuestionId = new Map(
    report.value.questions.map((question) => [question.questionId, question]),
  );
  return markers
    .map((marker) => {
      const question = byQuestionId.get(marker.marker);
      return question
        ? {
            order: question.order,
            prompt: question.prompt,
            correct: question.correct,
            unanswered: question.unanswered,
            averageScore: marker.averageScore,
            sampleCount: marker.sampleCount,
          }
        : null;
    })
    .filter(Boolean)
    .sort((left, right) => right.averageScore - left.averageScore)
    .slice(0, 5);
});

function highlightNote(item) {
  if (item.unanswered) return "该题未作答";
  return item.correct ? "该题最终答对，过程可能有卡壳" : "该题同时也是失分题";
}

const sortedMastery = computed(() =>
  [...(report.value?.mastery || [])].sort(
    (left, right) => left.masteryPercent - right.masteryPercent,
  ),
);

const weakestTopics = computed(() =>
  sortedMastery.value
    .filter((item) => item.masteryPercent < 80)
    .slice(0, 3)
    .map((item) => item.knowledgePoint),
);

const personalizedPracticeTarget = computed(() => ({
  name: "personalized-practice",
  query: {
    topic: weakestTopics.value.join("、") || report.value?.paperTitle,
    sourceAttemptId: report.value?.attemptId,
  },
}));

const viewedQuestionOrders = computed(() => {
  const viewedIds = new Set(report.value?.viewedAnswerQuestionIds || []);
  return (report.value?.questions || [])
    .filter((question) => viewedIds.has(question.questionId))
    .map((question) => question.order);
});

function practiceTargetFor(question) {
  return {
    name: "personalized-practice",
    query: {
      topic: question.knowledgePoints.join("、"),
      sourceAttemptId: report.value?.attemptId,
      sourceQuestionId: question.questionId,
    },
  };
}

function resultLabel(question) {
  if (question.unanswered) return "未作答";
  if (question.correct) return "完全正确";
  if (Number(question.score) > 0) return "部分得分";
  return "回答错误";
}

function resultClass(question) {
  if (question.unanswered) return "result-unanswered";
  if (question.correct) return "result-correct";
  if (Number(question.score) > 0) return "result-partial";
  return "result-wrong";
}

function displayScore(value) {
  const number = Number(value || 0);
  return Number.isInteger(number)
    ? number
    : number.toFixed(2).replace(/0+$/, "").replace(/\.$/, "");
}

function formatDuration(seconds) {
  const safe = Math.max(0, Number(seconds) || 0);
  const hours = Math.floor(safe / 3600);
  const minutes = Math.floor((safe % 3600) / 60);
  const remain = Math.floor(safe % 60);
  if (hours) return `${hours} 小时 ${minutes} 分`;
  if (minutes) return `${minutes} 分 ${remain} 秒`;
  return `${remain} 秒`;
}

async function loadReport() {
  loading.value = true;
  error.value = "";
  try {
    report.value = await getFixedExamReport(route.params.attemptId);
    await loadStateReports();
  } catch (requestError) {
    error.value =
      requestError?.response?.data?.message ||
      requestError?.message ||
      "请稍后重试";
  } finally {
    loading.value = false;
  }
}

async function loadStateReports() {
  if (!report.value) return;
  stateReports.value = await listLearningStateReports({
    contextType: "FIXED_EXAM",
    contextKey: `${report.value.paperCode}:attempt:${report.value.attemptId}`,
  }).catch(() => []);
}

onMounted(loadReport);
</script>

<style scoped>
.report-page,
.report-main,
.report-section,
.question-review-section,
.question-review {
  display: grid;
  gap: var(--vt-space-5);
}

.report-state {
  display: grid;
  justify-items: start;
  gap: var(--vt-space-3);
}

.report-state p,
.report-hero p {
  margin: 0;
  color: var(--vt-text-secondary);
  line-height: 1.7;
}

.report-hero,
.section-heading,
.question-review-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--vt-space-5);
}

.report-hero h1,
.section-heading h2,
.question-review h3 {
  margin: 0;
}

.report-hero > div:first-child {
  display: grid;
  gap: var(--vt-space-2);
  max-width: 760px;
}

.score-block {
  display: flex;
  align-items: baseline;
  white-space: nowrap;
}

.score-block strong {
  color: var(--vt-accent-teal-dark);
  font-size: clamp(2.4rem, 5vw, 4.5rem);
  line-height: 1;
}

.score-block span {
  color: var(--vt-text-secondary);
  font-weight: var(--vt-font-semibold);
}

.report-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--vt-space-4);
}

.report-metrics article {
  display: grid;
  gap: var(--vt-space-2);
}

.report-metrics span {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-sm);
}

.report-metrics strong {
  font-size: var(--vt-text-2xl);
}

.integrity-note {
  border-color: rgba(245, 158, 11, 0.35);
  background: rgba(245, 158, 11, 0.06);
}

.integrity-note p {
  margin: var(--vt-space-2) 0 0;
  color: var(--vt-text-secondary);
}

.report-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 220px;
  align-items: start;
  gap: var(--vt-space-5);
}

.section-heading > div {
  display: grid;
  gap: 2px;
}

.mastery-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-4);
}

.mastery-list article {
  display: grid;
  gap: var(--vt-space-2);
  padding: var(--vt-space-3);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
}

.mastery-list header {
  display: flex;
  justify-content: space-between;
  gap: var(--vt-space-2);
}

.mastery-list article > span,
.mastery-list header span {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.mastery-track {
  height: 8px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--vt-bg-secondary);
}

.mastery-track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--vt-accent-teal);
}

.diagnosis-grid,
.review-details,
.answer-comparison {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-4);
}

.state-note {
  margin: 0;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
  line-height: 1.65;
}

.state-highlights {
  display: grid;
  gap: var(--vt-space-2);
}

.state-highlights ul {
  margin: 0;
  padding-left: 1.2rem;
  display: grid;
  gap: var(--vt-space-2);
  font-size: var(--vt-text-sm);
  line-height: 1.6;
}

.state-highlights a {
  color: var(--vt-accent-teal-dark, #0f766e);
  font-weight: 600;
}

.diagnosis-grid > div,
.review-details > section,
.answer-comparison > section {
  padding: var(--vt-space-4);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
}

.diagnosis-grid h3,
.review-details h3,
.answer-comparison h3,
.scoring-points h3 {
  margin: 0 0 var(--vt-space-2);
  font-size: var(--vt-text-base);
}

.diagnosis-grid ul,
.review-details ul,
.scoring-points ul,
.source-list {
  display: grid;
  gap: var(--vt-space-2);
  margin: 0;
  padding-left: 1.25rem;
}

.review-title {
  align-items: end;
}

.question-review {
  scroll-margin-top: 92px;
}

.question-review-heading > div {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--vt-space-2);
}

.question-number {
  font-weight: var(--vt-font-bold);
}

.result-label,
.viewed-label,
.scoring-points li > span:first-child {
  padding: 3px 8px;
  border-radius: 999px;
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
}

.result-correct,
.point-achieved {
  background: rgba(22, 163, 74, 0.1);
  color: #15803d;
}

.result-partial {
  background: rgba(37, 99, 235, 0.1);
  color: #1d4ed8;
}

.result-wrong,
.point-missed {
  background: rgba(220, 38, 38, 0.1);
  color: #b91c1c;
}

.grading-review-note {
  margin: .8rem 0 0;
  padding: .7rem .8rem;
  border-radius: .55rem;
  background: rgba(245, 158, 11, .08);
  color: #92400e;
  font-size: .8rem;
  line-height: 1.55;
}

.result-unanswered,
.viewed-label {
  background: rgba(245, 158, 11, 0.12);
  color: #b45309;
}

.question-prompt {
  font-size: 1.03rem;
}

.answer-comparison pre {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--vt-text-secondary);
  font: inherit;
  line-height: 1.65;
}

.scoring-points li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--vt-space-3);
}

.question-review details {
  padding: var(--vt-space-3) var(--vt-space-4);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
}

.question-review summary {
  cursor: pointer;
  font-weight: var(--vt-font-semibold);
}

.validation-details p {
  color: var(--vt-text-secondary);
}

.source-list a {
  color: var(--vt-accent-teal-dark);
}

.question-review-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}

.report-aside {
  position: sticky;
  top: 90px;
  display: grid;
  gap: var(--vt-space-4);
}

.report-aside nav {
  display: grid;
  gap: var(--vt-space-2);
}

.report-aside nav a {
  display: flex;
  justify-content: space-between;
  gap: var(--vt-space-2);
  padding: var(--vt-space-2);
  border-radius: var(--vt-radius-md);
  color: inherit;
  text-decoration: none;
}

.report-aside nav small {
  color: inherit;
}

@media (max-width: 960px) {
  .report-layout {
    grid-template-columns: 1fr;
  }

  .report-aside {
    position: static;
    grid-row: 1;
  }

  .report-aside nav {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .report-aside nav a {
    display: grid;
  }
}

@media (max-width: 720px) {
  .report-hero,
  .section-heading,
  .question-review-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .report-metrics,
  .mastery-list,
  .diagnosis-grid,
  .review-details,
  .answer-comparison {
    grid-template-columns: 1fr;
  }

  .report-aside nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .scoring-points li {
    grid-template-columns: 1fr auto;
  }

  .scoring-points li > span:first-child {
    grid-column: 1 / -1;
    justify-self: start;
  }
}
</style>
