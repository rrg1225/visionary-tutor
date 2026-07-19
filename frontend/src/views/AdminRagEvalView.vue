<template>
  <section class="rag-eval-view">
    <header class="rag-header">
      <div>
        <span class="vt-eyebrow">RAG Evaluation</span>
        <h1 class="vt-title">RAG 指标闭环</h1>
        <p class="vt-text-muted">展示 latest 评估报告：引用正确率、忠实度、拒答、Top-K、BM25 fallback 和延迟。</p>
      </div>
      <button type="button" class="vt-btn vt-btn-outline" :disabled="loading" @click="loadReport">
        {{ loading ? '刷新中' : '刷新' }}
      </button>
    </header>

    <p v-if="error" class="rag-error">{{ error }}</p>

    <section v-if="summaryItems.length" class="metric-grid">
      <div v-for="item in summaryItems" :key="item.key" class="metric-cell">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </div>
    </section>

    <section class="report-paths">
      <p>JSON: {{ report?.jsonPath || '未生成' }}</p>
      <p>Markdown: {{ report?.markdownPath || '未生成' }}</p>
    </section>

    <section v-if="report?.rows?.length" class="rows-panel">
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>Query</th>
            <th>Citation</th>
            <th>Faithfulness</th>
            <th>Refusal</th>
            <th>Latency</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in report.rows" :key="row.id">
            <td>{{ row.id }}</td>
            <td>{{ row.query }}</td>
            <td>{{ row.citation_correct ? 'Y' : 'N' }}</td>
            <td>{{ row.answer_faithfulness }}</td>
            <td>{{ row.refusal_correct ? 'Y' : 'N' }}</td>
            <td>{{ row.latency_ms ?? '-' }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <section v-if="report?.markdown" class="markdown-panel">
      <h2>Markdown Report</h2>
      <pre>{{ report.markdown }}</pre>
    </section>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { fetchLatestRagEval } from '../api/ragEval'
import { toastError } from '../utils/toast'

const loading = ref(false)
const error = ref('')
const report = ref(null)

const summaryItems = computed(() => {
  const s = report.value?.summary || {}
  return [
    ['dataset_count', 'Gold Set', s.dataset_count],
    ['citation_correctness', 'Citation', s.citation_correctness],
    ['answer_faithfulness', 'Faithfulness', s.answer_faithfulness],
    ['refusal_correctness', 'Refusal', s.refusal_correctness],
    ['top_k_recall', 'Top-K', s.top_k_recall],
    ['p95_latency_ms', 'P95 Latency', s.p95_latency_ms ? `${s.p95_latency_ms} ms` : '-'],
    ['bm25_fallback_hit_rate', 'BM25 fallback', s.bm25_fallback_hit_rate],
    ['unsupported_query_refusal_rate', 'Unsupported refusal', s.unsupported_query_refusal_rate],
  ].map(([key, label, value]) => ({ key, label, value: value ?? '-' }))
})

async function loadReport() {
  loading.value = true
  error.value = ''
  try {
    report.value = await fetchLatestRagEval()
  } catch (err) {
    error.value = err?.response?.data?.message || err?.message || 'RAG 评估报告加载失败'
    toastError(error.value)
  } finally {
    loading.value = false
  }
}

onMounted(loadReport)
</script>

<style scoped>
.rag-eval-view {
  max-width: 1120px;
  margin: 0 auto;
  padding: 24px 16px 48px;
  display: grid;
  gap: 16px;
}

.rag-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
}

.metric-cell,
.report-paths,
.rows-panel,
.markdown-panel,
.rag-error {
  border: 1px solid var(--vt-border-subtle);
  border-radius: 8px;
  background: var(--vt-surface);
}

.metric-cell {
  padding: 12px;
  display: grid;
  gap: 4px;
}

.metric-cell span {
  color: var(--vt-text-muted);
  font-size: 13px;
}

.metric-cell strong {
  font-size: 22px;
}

.report-paths {
  padding: 12px;
  color: var(--vt-text-muted);
}

.rows-panel {
  overflow: auto;
}

table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

th,
td {
  text-align: left;
  padding: 10px 12px;
  border-bottom: 1px solid var(--vt-border-subtle);
  vertical-align: top;
}

.markdown-panel {
  padding: 16px;
}

.markdown-panel pre {
  max-height: 420px;
  overflow: auto;
  padding: 12px;
  border-radius: 6px;
  background: #0f172a;
  color: #e2e8f0;
}

.rag-error {
  padding: 12px;
  color: #b91c1c;
}

@media (max-width: 760px) {
  .rag-header {
    display: grid;
  }

  .metric-grid {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
