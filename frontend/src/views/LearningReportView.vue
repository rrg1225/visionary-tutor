<template>
  <section class="learning-report vt-container vt-section">
    <header class="page-header">
      <span class="vt-eyebrow">学习效果评估</span>
      <h1 class="vt-title">多维度学习报告</h1>
      <p class="vt-text-muted">
        基于测验正确率、掌握度变化与资源使用记录生成量化评估；配置 DeepSeek 后由大模型补充解读。
      </p>
      <p v-if="isDemoMode && hasReportContent" class="demo-notice" role="status">
        当前为示例数据，完成学习后将展示真实报告
      </p>
    </header>

    <section class="unified-report-index vt-card" aria-label="统一报告索引">
      <header>
        <div>
          <span class="vt-eyebrow">统一报告入口</span>
          <h2>状态、题卷、内容自测、文件评估与综合报告</h2>
        </div>
        <span>{{ unifiedReports.length }} 份可查看记录</span>
      </header>
      <div class="report-type-grid">
        <article v-for="item in unifiedReports" :key="item.id" :class="`report-type ${item.type}`">
          <span>{{ item.label }}</span>
          <strong>{{ item.title }}</strong>
          <p>{{ item.summary }}</p>
          <RouterLink v-if="item.to" class="vt-btn vt-btn-ghost vt-btn-sm" :to="item.to">查看详情</RouterLink>
          <small>{{ item.meta }}</small>
        </article>
        <p v-if="!unifiedReports.length" class="vt-text-muted">尚无任何报告记录；完成一次题卷、自测、文件评估或状态辅助后会自动汇入这里。</p>
      </div>
    </section>

    <section class="coverage-overview vt-card" aria-label="学习闭环覆盖范围">
      <header><div><span class="vt-eyebrow">真实学习闭环</span><h2>六类学习场景统一汇总</h2></div><span>记录来自真实作答、阅读、实验与状态报告</span></header>
      <div>
        <article v-for="item in coverageTypes" :key="item.label" :class="{ active: item.count > 0 }">
          <strong>{{ item.label }}</strong><span>{{ item.count > 0 ? `${item.count} 条证据` : '等待首次学习' }}</span>
        </article>
      </div>
    </section>

    <div v-if="loginRequired" class="vt-card report-gate">
      <h2>登录后查看学习效果评估</h2>
      <p class="vt-text-muted">
        学习报告需要正式账号，并完成至少一次互动练习或作业评测后才会生成雷达图与改进建议。
      </p>
      <div class="gate-actions">
        <RouterLink class="vt-btn vt-btn-primary" :to="{ path: '/auth', query: { mode: 'register', redirect: '/learning-report' } }">
          注册 / 登录
        </RouterLink>
        <RouterLink class="vt-btn vt-btn-outline" to="/assessment-fill">先去完成作业评测</RouterLink>
      </div>
    </div>

    <div v-else-if="error" class="vt-card report-error" role="alert">{{ error }}</div>

    <EmptyStateGuide
      v-else-if="reportIsEmpty"
      icon="report"
      title="还没有学习效果数据"
      description="完成一次互动练习、对话建档或作业评测后，系统会汇总掌握度指标并生成雷达图与改进建议。"
    >
      <template #actions>
        <RouterLink class="vt-btn vt-btn-primary" to="/learn">返回学习循环</RouterLink>
        <RouterLink class="vt-btn vt-btn-outline" to="/assessment-fill">完成作业评测</RouterLink>
        <button type="button" class="vt-btn vt-btn-outline" data-testid="btn-demo-report" @click="loadDemoReport">
          查看示例学习报告（答辩预览）
        </button>
      </template>
    </EmptyStateGuide>

    <div v-else class="report-grid">
      <section class="vt-card report-summary" aria-label="总体评价">
        <h2>总体评价</h2>
        <p>{{ summary || '正在整理评估摘要…' }}</p>
        <p v-if="metricsSummary" class="metrics-block">{{ metricsSummary }}</p>
        <p v-if="!llmUsed && !loading" class="vt-text-muted">当前为指标汇总模式（未调用 LLM 或 Key 未配置）；完成互动练习与对话后数据会更准确。</p>
      </section>

      <section
        v-if="prePostComparable || masteryCurvePoints.length"
        class="vt-card quant-evidence"
        aria-label="量化学习证据"
      >
        <h2>量化学习证据</h2>
        <div v-if="prePostComparable" class="pre-post-grid">
          <div class="pre-post-card">
            <span class="pre-post-label">前测均分</span>
            <strong class="pre-post-value">{{ prePostSummary.preTestAverage }}%</strong>
            <span class="pre-post-meta">{{ prePostSummary.preTestCount }} 次</span>
          </div>
          <div class="pre-post-card pre-post-delta">
            <span class="pre-post-label">提升幅度</span>
            <strong class="pre-post-value" :class="{ positive: prePostSummary.delta > 0 }">
              {{ prePostSummary.delta > 0 ? '+' : '' }}{{ prePostSummary.delta }}%
            </strong>
          </div>
          <div class="pre-post-card">
            <span class="pre-post-label">后测均分</span>
            <strong class="pre-post-value">{{ prePostSummary.postTestAverage }}%</strong>
            <span class="pre-post-meta">{{ prePostSummary.postTestCount }} 次</span>
          </div>
        </div>
        <div v-if="masteryCurvePoints.length" class="mastery-curve-wrap">
          <h3 class="curve-title">掌握度变化曲线</h3>
          <svg viewBox="0 0 320 140" class="mastery-curve-chart" role="img" aria-label="掌握度变化折线图">
            <line x1="24" y1="116" x2="296" y2="116" stroke="rgba(148,163,184,0.5)" stroke-width="1" />
            <line x1="24" y1="24" x2="24" y2="116" stroke="rgba(148,163,184,0.5)" stroke-width="1" />
            <polyline
              v-if="masteryPolyline"
              :points="masteryPolyline"
              fill="none"
              stroke="#2563eb"
              stroke-width="2"
              stroke-linejoin="round"
            />
            <g v-for="(point, idx) in masteryCurvePoints" :key="idx">
              <circle :cx="point.x" :cy="point.y" r="4" fill="#2563eb" />
              <text :x="point.x" :y="point.y - 8" text-anchor="middle" class="curve-point-label">
                {{ point.masteryPercent }}%
              </text>
            </g>
          </svg>
          <ul class="curve-legend">
            <li v-for="(point, idx) in masteryCurvePoints" :key="`legend-${idx}`">
              <span class="curve-tag">{{ point.label }}</span>
              {{ point.concept }} · {{ point.masteryPercent }}%
            </li>
          </ul>
        </div>
      </section>

      <section class="vt-card radar-panel" :class="{ 'radar-insufficient': insufficientData }" aria-label="掌握度雷达图">
        <h2>掌握度雷达</h2>
        <p v-if="radarLoading" class="vt-text-muted">正在同步知识追踪掌握度…</p>
        <p v-if="insufficientData && radarPoints.length" class="insufficient-note">
          有效学习指标不足（当前 {{ meaningfulMetricCount }} 条，至少需要 2 条）。系统不会编造雷达分值。
        </p>
        <svg v-if="radarPoints.length" viewBox="0 0 240 240" class="radar-chart" role="img" aria-label="学习掌握度雷达图">
          <g v-for="ring in [0.25, 0.5, 0.75, 1]" :key="ring">
            <polygon
              :points="ringPolygon(ring)"
              fill="none"
              stroke="rgba(148,163,184,0.35)"
              stroke-width="1"
            />
          </g>
          <polygon :points="polygonPoints" fill="rgba(59,130,246,0.25)" stroke="#3b82f6" stroke-width="2" />
          <g v-for="point in radarPoints" :key="point.axis">
            <circle :cx="point.x" :cy="point.y" r="3" fill="#2563eb" />
            <text :x="point.labelX" :y="point.labelY" text-anchor="middle" class="radar-label">
              {{ point.axis }} {{ point.value }}
            </text>
          </g>
        </svg>
        <EmptyStateGuide
          v-else-if="!loading && (insufficientData || !radarData.length)"
          compact
          icon="report"
          title="指标尚不足以绘制雷达"
          :description="`有效学习指标 ${meaningfulMetricCount} 条，至少需要 2 条。请继续练习或完成评测后再查看。`"
        >
          <template #actions>
            <button type="button" class="vt-btn vt-btn-primary" :disabled="loading" @click="loadAssessment">
              重新评估
            </button>
            <button
              type="button"
              class="vt-btn vt-btn-outline"
              data-testid="btn-demo-report-radar"
              @click="loadDemoReport"
            >
              查看示例学习报告（答辩预览）
            </button>
          </template>
        </EmptyStateGuide>
      </section>

      <section v-if="suggestions.length" class="vt-card suggestions" aria-label="改进建议">
        <h2>改进建议</h2>
        <ul>
          <li v-for="(item, idx) in suggestions" :key="idx">{{ item }}</li>
        </ul>
        <p v-if="shouldReplan" class="replan-note">
          系统建议重规划学习路径：{{ replanReason || '根据近期学习指标自动调整' }}
        </p>
      </section>
    </div>

    <div class="report-actions">
      <button class="vt-btn vt-btn-outline" :disabled="loading" @click="loadAssessment">
        {{ loading ? '刷新中...' : '重新评估' }}
      </button>
      <RouterLink class="vt-btn vt-btn-primary" to="/learn">返回学习循环</RouterLink>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import EmptyStateGuide from '../components/EmptyStateGuide.vue'
import { useLearningReport } from '../composables/useLearningReport'
import { listFixedExamReports } from '../api/fixedExams'
import { listLearningStateReports } from '../api/learningState'
import { useLearningSessionStore } from '../stores/learningSession'

const learningSession = useLearningSessionStore()
const fixedExamReports = ref([])
const contentReports = ref([])
const stateReports = ref([])
const coverageTypes = computed(() => {
  const resources = learningSession.resourceCards || []
  const states = stateReports.value
  const stateCount = (type) => states.filter((item) => item.contextType === type).length
  const resourceCount = (type) => resources.filter((item) => item.artifactType === type).length
  return [
    { label: '固定题卷', count: fixedExamReports.value.length },
    { label: 'AI 练习', count: resourceCount('QUIZ') },
    { label: '系统教材', count: contentReports.value.length + stateCount('SYSTEM_KNOWLEDGE') },
    { label: '社区教材', count: stateCount('COMMUNITY_TEXTBOOK') },
    { label: '代码实验', count: resourceCount('CODE_PRACTICE') },
    { label: '动画实验', count: resourceCount('VISUALIZATION') },
  ]
})

const {
  loading,
  radarLoading,
  error,
  loginRequired,
  summary,
  metricsSummary,
  suggestions,
  radarPoints,
  polygonPoints,
  shouldReplan,
  replanReason,
  llmUsed,
  radarData,
  insufficientData,
  meaningfulMetricCount,
  isDemoMode,
  prePostSummary,
  prePostComparable,
  masteryCurvePoints,
  masteryPolyline,
  loadAssessment,
  loadDemoReport,
} = useLearningReport()

const reportIsEmpty = computed(() =>
  !isDemoMode.value
  && !loading.value
  && !summary.value
  && !metricsSummary.value
  && !radarPoints.value.length
  && !suggestions.value.length
  && !prePostComparable.value
  && !masteryCurvePoints.value.length
)

const hasReportContent = computed(() =>
  Boolean(
    summary.value
    || metricsSummary.value
    || radarPoints.value.length
    || suggestions.value.length
    || prePostComparable.value
    || masteryCurvePoints.value.length
  )
)

const unifiedReports = computed(() => {
  const rows = []
  for (const report of fixedExamReports.value) {
    rows.push({
      id: `fixed-${report.attemptId}`,
      type: 'fixed',
      label: '固定综合题卷',
      title: report.paperTitle,
      summary: `${report.totalScore}/${report.maxScore} 分 · 正确率 ${report.accuracyPercent}%`,
      meta: `${report.totalDurationSeconds || 0} 秒 · attempt ${report.attemptId}`,
      to: `/questions/attempts/${report.attemptId}/report`,
    })
  }
  for (const report of contentReports.value) {
    rows.push({
      id: `content-${report.slug}`,
      type: 'content',
      label: '系统内容自测',
      title: report.title,
      summary: `${report.score}/${report.maxScore} 分 · ${report.weakSectionIds?.length || 0} 个待复习章节`,
      meta: report.submittedAt ? new Date(report.submittedAt).toLocaleString('zh-CN') : '',
      to: `/knowledge-content/${report.slug}#self-test`,
    })
  }
  for (const report of stateReports.value) {
    rows.push({
      id: `state-${report.reportId}`,
      type: 'state',
      label: '学习状态辅助',
      title: report.contextTitle,
      summary: report.headline,
      meta: `${report.sampleCount} 个有效样本 · ${report.durationSeconds} 秒 · ${report.contextType}/${report.contextKey}`,
      to: stateReportTarget(report),
    })
  }
  if (learningSession.diagnosticReport) {
    rows.push({
      id: `file-${learningSession.diagnosticReport.id || 'current'}`,
      type: 'file',
      label: '文件/作业评估',
      title: learningSession.currentSession?.assessmentFileName || '最近一次文件评估',
      summary: learningSession.diagnosticReport.reasoningTrace || '已生成知识薄弱点与纠错建议。',
      meta: `session ${learningSession.currentSessionId || '-'}`,
      to: '/assessment-fill',
    })
  }
  if (hasReportContent.value) {
    rows.unshift({
      id: 'comprehensive-current',
      type: 'comprehensive',
      label: '综合学习报告',
      title: '当前学习效果综合评估',
      summary: summary.value || metricsSummary.value || '综合掌握度、练习与资源使用数据。',
      meta: '当前页面下方为完整报告',
      to: '',
    })
  }
  return rows
})

function restoreLocalReports() {
  try {
    const progress = JSON.parse(localStorage.getItem('vt_knowledge_progress') || '{}')
    contentReports.value = Object.values(progress).flatMap((entry) => {
      const saved = JSON.parse(localStorage.getItem(`vt_knowledge_${entry.slug}_report`) || 'null')
      return saved ? [{ ...saved, slug: entry.slug, title: entry.title }] : []
    })
    stateReports.value = JSON.parse(localStorage.getItem('vt_learning_state_reports') || '[]')
  } catch {
    contentReports.value = []
    stateReports.value = []
  }
}

/** 状态报告跳回它发生时的学习场景（问题23：报告可溯源）。 */
function stateReportTarget(report) {
  switch (report.contextType) {
    case 'FIXED_EXAM':
      // contextKey 形如 "paperCode" 或 "paperCode:attempt:{id}"，取题卷码部分。
      return `/questions/papers/${String(report.contextKey || '').split(':attempt:')[0]}`
    case 'SYSTEM_KNOWLEDGE':
      return `/knowledge-content/${report.contextKey}`
    case 'PERSONALIZED_PRACTICE':
      return '/questions/personalized'
    case 'AI_TUTOR':
      return '/learn'
    case 'INTERACTIVE_LAB':
      return '/labs'
    case 'COMMUNITY_TEXTBOOK':
      return String(report.contextKey || '').startsWith('community:')
        ? `/library/${String(report.contextKey).split(':')[1]}`
        : '/library'
    default:
      return ''
  }
}

async function loadStateReports() {
  const backendReports = await listLearningStateReports().catch(() => [])
  if (Array.isArray(backendReports) && backendReports.length) {
    stateReports.value = backendReports.map((report) => ({
      ...report,
      reportId: report.id,
    }))
  }
}

onMounted(async () => {
  restoreLocalReports()
  await learningSession.hydrateGeneratedResources().catch(() => null)
  fixedExamReports.value = await listFixedExamReports({ silent: true }).catch(() => [])
  await loadStateReports()
})

function ringPolygon(scale) {
  const center = 120
  const radius = 88 * scale
  const count = radarPoints.value.length || 5
  return Array.from({ length: count }, (_, index) => {
    const angle = (Math.PI * 2 * index) / count - Math.PI / 2
    const x = center + Math.cos(angle) * radius
    const y = center + Math.sin(angle) * radius
    return `${x},${y}`
  }).join(' ')
}
</script>

<style scoped>
.learning-report {
  display: grid;
  gap: var(--vt-space-6);
  max-width: 1180px;
}
.coverage-overview { padding: var(--vt-space-5); display: grid; gap: var(--vt-space-4); }
.coverage-overview > header { display: flex; align-items: end; justify-content: space-between; gap: 1rem; }
.coverage-overview h2 { margin: .25rem 0 0; }
.coverage-overview header > span { color: var(--vt-text-tertiary); font-size: var(--vt-text-xs); }
.coverage-overview > div { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .6rem; }
.coverage-overview article { display: grid; gap: .25rem; padding: .8rem; border-radius: .6rem; background: var(--vt-bg-secondary); color: var(--vt-text-tertiary); }
.coverage-overview article.active { background: rgba(13,148,136,.08); color: var(--vt-text-primary); }
.coverage-overview article span { font-size: var(--vt-text-xs); }
.unified-report-index { padding: var(--vt-space-5); display: grid; gap: var(--vt-space-4); }
.unified-report-index > header { display: flex; justify-content: space-between; gap: var(--vt-space-3); }
.unified-report-index h2 { margin: .3rem 0 0; font-size: var(--vt-text-lg); }
.report-type-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: var(--vt-space-3); }
.report-type { display: grid; gap: .4rem; padding: var(--vt-space-3); border-radius: var(--vt-radius-md); background: rgba(59,130,246,.05); border-left: 3px solid #3b82f6; }
.report-type.state { border-color: #0d9488; }.report-type.content { border-color: #7c3aed; }.report-type.fixed { border-color: #d97706; }.report-type.comprehensive { border-color: #2563eb; }
.report-type > span, .report-type small { color: var(--vt-text-tertiary); font-size: var(--vt-text-xs); }
.report-type p { margin: 0; color: var(--vt-text-secondary); line-height: 1.5; font-size: var(--vt-text-sm); }

.page-header {
  display: grid;
  gap: var(--vt-space-3);
}

.demo-notice {
  margin: 0;
  padding: var(--vt-space-2) var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: rgba(245, 158, 11, 0.12);
  border: 1px solid rgba(245, 158, 11, 0.35);
  color: #b45309;
  font-size: var(--vt-text-sm);
  font-weight: var(--vt-font-medium);
}

.report-grid {
  display: grid;
  gap: var(--vt-space-4);
}

.report-summary,
.radar-panel,
.suggestions,
.report-error,
.quant-evidence {
  padding: var(--vt-space-6);
}

.quant-evidence h2 {
  margin: 0 0 var(--vt-space-4);
  font-size: var(--vt-text-lg);
}

.pre-post-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: var(--vt-space-3);
  margin-bottom: var(--vt-space-4);
}

.pre-post-card {
  display: grid;
  gap: var(--vt-space-1);
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: rgba(59, 130, 246, 0.06);
  text-align: center;
}

.pre-post-delta {
  background: rgba(16, 185, 129, 0.08);
}

.pre-post-label {
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
}

.pre-post-value {
  font-size: var(--vt-text-xl);
  color: var(--vt-text-primary);
}

.pre-post-value.positive {
  color: #059669;
}

.pre-post-meta {
  font-size: var(--vt-text-xs);
  color: var(--vt-text-muted);
}

.curve-title {
  margin: 0 0 var(--vt-space-2);
  font-size: var(--vt-text-base);
  font-weight: var(--vt-font-medium);
}

.mastery-curve-chart {
  width: 100%;
  max-width: 420px;
  display: block;
  margin: 0 auto;
}

.curve-point-label {
  font-size: 9px;
  fill: var(--vt-text-secondary);
}

.curve-legend {
  margin: var(--vt-space-3) 0 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: var(--vt-space-1);
  font-size: var(--vt-text-sm);
  color: var(--vt-text-secondary);
}

.curve-tag {
  display: inline-block;
  min-width: 3.5rem;
  margin-right: var(--vt-space-2);
  padding: 0 var(--vt-space-1);
  border-radius: var(--vt-radius-sm);
  background: rgba(59, 130, 246, 0.12);
  font-size: var(--vt-text-xs);
  color: #2563eb;
}

.report-summary h2,
.radar-panel h2,
.suggestions h2 {
  margin: 0 0 var(--vt-space-3);
  font-size: var(--vt-text-lg);
}

.metrics-block {
  margin-top: var(--vt-space-3);
  white-space: pre-wrap;
  font-size: var(--vt-text-sm);
  color: var(--vt-text-secondary);
}

.radar-chart {
  width: 100%;
  max-width: 360px;
  margin: 0 auto;
  display: block;
}

.radar-label {
  font-size: 10px;
  fill: var(--vt-text-secondary);
}

.suggestions ul {
  margin: 0;
  padding-left: 1.2rem;
  display: grid;
  gap: var(--vt-space-2);
}

.replan-note {
  margin-top: var(--vt-space-3);
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: rgba(59, 130, 246, 0.08);
  font-size: var(--vt-text-sm);
}

.radar-panel.radar-insufficient {
  border: 1px dashed rgba(148, 163, 184, 0.6);
}

.insufficient-note {
  margin: 0 0 var(--vt-space-3);
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: rgba(245, 158, 11, 0.1);
  font-size: var(--vt-text-sm);
}

.report-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-3);
  justify-content: flex-end;
}

.report-gate {
  padding: var(--vt-space-8);
  display: grid;
  gap: var(--vt-space-4);
  text-align: center;
}

.report-gate h2 {
  margin: 0;
  font-size: var(--vt-text-xl);
}

.gate-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-3);
  justify-content: center;
}
@media (max-width: 720px) {
  .coverage-overview > header { align-items: stretch; flex-direction: column; }
  .coverage-overview > div { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
