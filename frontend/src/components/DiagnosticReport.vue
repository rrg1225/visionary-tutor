<template>
  <PanelCard class="diagnostic-report" aria-label="knowledge diagnostic report">
    <PageHeader>
      <template #eyebrow>知识诊断</template>
      <template #badge>
        <StatusBadge variant="warning" class="diagnosis-badge">已持久化</StatusBadge>
      </template>
      <template #title>薄弱知识节点</template>
      <template #desc>基于最新视觉评测、对话难点与诊断报告，定位知识链薄弱环节。</template>
    </PageHeader>

    <div class="report-content">
      <aside class="evidence-panel" aria-label="assessment evidence">
        <div class="panel-section-title">评测证据</div>
        <p class="draft-caption">{{ evidenceView.caption }}</p>
        <div class="trace-note">{{ evidenceView.traceNote }}</div>
        <div class="code-preview">
          <div class="code-header">
            <span class="code-lang">Extracted</span>
            <span class="code-status vt-badge vt-badge-warning">证据</span>
          </div>
          <pre class="code-block"><code>{{ evidenceView.buggyCode }}</code></pre>
        </div>
      </aside>

      <div class="analysis-panel">
        <div class="panel-section-title">掌握度图谱</div>
        <div class="node-list">
          <article
            v-for="node in displayNodes"
            :key="`${node.name}-${node.layer}`"
            class="node-item"
            :class="`mastery-${getMasteryLevel(node.mastery)}`"
          >
            <div class="node-details">
              <div class="node-header">
                <strong class="node-name">{{ node.name }}</strong>
                <span class="node-layer">{{ node.layer }}</span>
              </div>
              <p v-if="node.hint || node.description" class="node-hint">
                {{ node.hint || node.description }}
              </p>
            </div>
            <div class="node-metrics">
              <span class="progress-value" :class="`text-${getMasteryLevel(node.mastery)}`">
                {{ node.mastery }}%
              </span>
            </div>
          </article>
        </div>

        <article class="analysis-body" aria-label="diagnostic analysis">
          <div class="panel-section-title">诊断分析</div>
          <MarkdownPanel :content="analysisHtml" />
        </article>
      </div>
    </div>
  </PanelCard>
</template>

<script setup>
import { computed } from 'vue'
import { renderSimpleMarkdown } from '../utils/simpleMarkdown'
import PanelCard from './common/PanelCard.vue'
import PageHeader from './common/PageHeader.vue'
import StatusBadge from './common/StatusBadge.vue'
import MarkdownPanel from './common/MarkdownPanel.vue'

const props = defineProps({
  nodes: {
    type: Array,
    default: () => [],
  },
  analysis: {
    type: String,
    default: '',
  },
  evidence: {
    type: Object,
    default: () => ({}),
  },
})

const evidenceView = computed(() => ({
  caption: props.evidence.caption || 'No uploaded evidence summary yet.',
  traceNote: props.evidence.traceNote || 'Waiting for visual assessment evidence.',
  buggyCode: props.evidence.buggyCode || '# No code evidence extracted yet.',
}))

const displayNodes = computed(() =>
  (props.nodes || []).map((node) => ({
    hint: '',
    trend: 'stable',
    mastery: 0,
    ...node,
  })),
)

const analysisHtml = computed(() =>
  renderSimpleMarkdown(props.analysis || 'No diagnostic analysis has been persisted yet.'),
)

function getMasteryLevel(mastery) {
  if (mastery >= 80) return 'excellent'
  if (mastery >= 60) return 'good'
  if (mastery >= 40) return 'warning'
  return 'critical'
}
</script>

<style scoped>
.diagnostic-report {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-5);
  padding: var(--vt-space-5);
}

.diagnosis-badge {
  font-size: 10px;
}

.report-content {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--vt-space-5);
}

.evidence-panel,
.analysis-panel {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-4);
}

.panel-section-title {
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
  text-transform: uppercase;
  color: var(--vt-text-tertiary);
}

.draft-caption,
.trace-note,
.node-hint {
  margin: 0;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
}

.code-preview {
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  overflow: hidden;
}

.code-header {
  display: flex;
  justify-content: space-between;
  padding: var(--vt-space-2) var(--vt-space-3);
  background: var(--vt-bg-secondary);
}

.code-block {
  margin: 0;
  padding: var(--vt-space-3);
  overflow: auto;
  font-size: var(--vt-text-xs);
}

.node-list {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-3);
}

.node-item {
  display: flex;
  justify-content: space-between;
  gap: var(--vt-space-3);
  padding: var(--vt-space-3);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
}

.node-header {
  display: flex;
  align-items: center;
  gap: var(--vt-space-2);
  flex-wrap: wrap;
}

.node-name {
  color: var(--vt-text-primary);
}

.node-layer {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.progress-value {
  font-weight: var(--vt-font-bold);
}

.text-excellent,
.text-good {
  color: var(--vt-accent-green);
}

.text-warning {
  color: var(--vt-accent-amber);
}

.text-critical {
  color: var(--vt-accent-red);
}

@media (min-width: 1024px) {
  .report-content {
    grid-template-columns: 0.85fr 1.15fr;
  }
}
</style>
