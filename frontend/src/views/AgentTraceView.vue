<template>
  <section class="agent-trace-view">
    <header class="trace-header">
      <div>
        <span class="vt-eyebrow">Agent Audit</span>
        <h1 class="vt-title">多智能体协作审计</h1>
        <p class="vt-text-muted">按 runId 或 sessionId 展开每轮输入、输出、RAG 证据、Critic 意见和降级记录。</p>
      </div>
      <form class="trace-query" @submit.prevent="loadTrace">
        <input v-model.trim="query.runId" type="text" placeholder="runId" />
        <input v-model.number="query.learningSessionId" type="number" min="1" placeholder="sessionId" />
        <button class="vt-btn vt-btn-primary" type="submit" :disabled="loading">
          {{ loading ? '加载中' : '查询' }}
        </button>
      </form>
    </header>

    <p v-if="error" class="trace-error" role="alert">{{ error }}</p>

    <section v-if="trace" class="score-strip" aria-label="质量评分">
      <div v-for="item in scoreItems" :key="item.label" class="score-item">
        <span>{{ item.label }}</span>
        <strong>{{ Math.round(item.value * 100) }}%</strong>
      </div>
    </section>

    <section v-if="trace?.fallbackEvents?.length" class="fallback-panel">
      <h2>降级事件</h2>
      <article v-for="item in trace.fallbackEvents" :key="`${item.agentName}-${item.stepOrder}`" class="fallback-row">
        <strong>{{ item.agentName }} #{{ item.stepOrder }}</strong>
        <p>{{ item.reason }}</p>
      </article>
    </section>

    <section v-if="trace?.steps?.length" class="trace-timeline">
      <article v-for="step in trace.steps" :key="step.id || `${step.agentName}-${step.stepOrder}`" class="trace-step">
        <header>
          <span class="step-index">#{{ step.stepOrder }}</span>
          <h2>{{ step.agentName }}</h2>
          <span class="step-status">{{ step.status || 'COMPLETED' }}</span>
        </header>
        <div class="step-grid">
          <div>
            <h3>Input</h3>
            <p>{{ step.inputSummary || '无' }}</p>
          </div>
          <div>
            <h3>Output</h3>
            <p>{{ step.outputSummary || '无' }}</p>
          </div>
          <div>
            <h3>Critic</h3>
            <p>{{ step.critique || '无' }}</p>
          </div>
          <div>
            <h3>RAG Evidence</h3>
            <p>{{ step.ragEvidence?.length ? step.ragEvidence.join(', ') : '无引用记录' }}</p>
          </div>
        </div>
        <details v-if="step.revisionDiff || step.auditTraceJson">
          <summary>结构化审计 JSON</summary>
          <pre>{{ prettyAudit(step) }}</pre>
        </details>
      </article>
    </section>

    <section v-else-if="!loading" class="empty-state">
      <p>{{ trace?.summary || '暂无 Agent trace。先生成一次资源，或使用 demo seed 初始化。' }}</p>
    </section>
  </section>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { fetchAgentTrace } from '../api/agentAudit'
import { toastError } from '../utils/toast'

const loading = ref(false)
const error = ref('')
const trace = ref(null)
const query = reactive({
  runId: '',
  learningSessionId: Number(localStorage.getItem('vt_current_session_id') || 0) || null,
})

const scoreItems = computed(() => {
  const score = trace.value?.qualityScore || {}
  return [
    { label: '覆盖度', value: score.coverage || 0 },
    { label: '引用率', value: score.citationRate || 0 },
    { label: '个性化匹配', value: score.personalizationMatch || 0 },
    { label: '可执行性', value: score.executability || 0 },
    { label: '综合', value: score.overall || 0 },
  ]
})

async function loadTrace() {
  loading.value = true
  error.value = ''
  try {
    const params = {}
    if (query.runId) params.runId = query.runId
    if (!params.runId && query.learningSessionId) params.learningSessionId = query.learningSessionId
    trace.value = await fetchAgentTrace(params)
  } catch (err) {
    error.value = err?.response?.data?.message || err?.message || 'Agent trace 加载失败'
    toastError(error.value)
  } finally {
    loading.value = false
  }
}

function prettyAudit(step) {
  if (!step.auditTraceJson) return step.revisionDiff || ''
  try {
    return JSON.stringify(JSON.parse(step.auditTraceJson), null, 2)
  } catch {
    return step.auditTraceJson
  }
}
</script>

<style scoped>
.agent-trace-view {
  max-width: 1120px;
  margin: 0 auto;
  padding: 24px 16px 48px;
  display: grid;
  gap: 16px;
}

.trace-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.trace-query {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.trace-query input {
  width: 180px;
  padding: 8px 10px;
  border: 1px solid var(--vt-border-subtle);
  border-radius: 6px;
}

.score-strip {
  display: grid;
  grid-template-columns: repeat(5, minmax(120px, 1fr));
  gap: 8px;
}

.score-item,
.fallback-panel,
.trace-step,
.empty-state,
.trace-error {
  border: 1px solid var(--vt-border-subtle);
  border-radius: 8px;
  background: var(--vt-surface);
}

.score-item {
  padding: 12px;
  display: grid;
  gap: 4px;
}

.score-item span {
  color: var(--vt-text-muted);
  font-size: 13px;
}

.score-item strong {
  font-size: 22px;
}

.fallback-panel {
  padding: 16px;
  display: grid;
  gap: 10px;
  border-color: rgba(217, 119, 6, 0.45);
}

.fallback-panel h2,
.trace-step h2,
.trace-step h3 {
  margin: 0;
}

.fallback-row p {
  margin: 4px 0 0;
  color: var(--vt-text-muted);
}

.trace-timeline {
  display: grid;
  gap: 12px;
}

.trace-step {
  padding: 16px;
  display: grid;
  gap: 12px;
}

.trace-step header {
  display: flex;
  align-items: center;
  gap: 10px;
}

.step-index,
.step-status {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 6px;
  background: var(--vt-bg-muted);
}

.step-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.step-grid > div {
  min-height: 96px;
  padding: 12px;
  border: 1px solid var(--vt-border-subtle);
  border-radius: 6px;
}

.step-grid p {
  margin: 6px 0 0;
  white-space: pre-wrap;
  color: var(--vt-text-muted);
}

details pre {
  max-height: 320px;
  overflow: auto;
  padding: 12px;
  border-radius: 6px;
  background: #0f172a;
  color: #e2e8f0;
}

.empty-state,
.trace-error {
  padding: 16px;
}

.trace-error {
  border-color: rgba(220, 38, 38, 0.4);
  color: #b91c1c;
}

@media (max-width: 760px) {
  .trace-header {
    display: grid;
  }

  .trace-query,
  .trace-query input {
    width: 100%;
  }

  .score-strip,
  .step-grid {
    grid-template-columns: 1fr;
  }
}
</style>
