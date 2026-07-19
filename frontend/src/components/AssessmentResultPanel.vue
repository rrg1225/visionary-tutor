<template>
  <PanelCard class="assessment-result" aria-label="测评闭环反馈">
    <header class="panel-heading">
      <span class="vt-eyebrow">测评闭环 · 反馈</span>
      <strong>{{ statusText }}</strong>
    </header>

    <div v-if="resultData" class="result-body">
      <article class="metric-row">
        <span>识别文本</span>
        <strong>{{ resultData.ocrText || resultData.ocr_text || '等待视觉模型返回 OCR' }}</strong>
      </article>
      <article class="metric-row">
        <span>易错类型</span>
        <strong>{{ resultData.errorPattern || resultData.error_pattern || '待分析' }}</strong>
      </article>
      <article class="metric-row">
        <span>置信度</span>
        <strong>{{ confidenceText }}</strong>
      </article>
      <p class="feedback">{{ resultData.correctiveFeedback || resultData.corrective_feedback || '评测完成后将展示针对性反馈。' }}</p>
    </div>

    <EmptyStateGuide
      v-else
      compact
      icon="assessment"
      title="等待作业评测"
      description="上传手写推导或代码截图后，错因会写回学情画像，驱动下一轮资源生成。"
    />
  </PanelCard>
</template>

<script setup>
import { computed } from 'vue'
import PanelCard from './common/PanelCard.vue'
import EmptyStateGuide from './EmptyStateGuide.vue'

const props = defineProps({
  response: {
    type: Object,
    default: null,
  },
})

const resultData = computed(() => props.response?.data ?? props.response)
const statusText = computed(() => props.response?.status || '等待提交')
const confidenceText = computed(() => {
  const value = resultData.value?.confidence
  if (typeof value !== 'number') return '待评估'
  return `${Math.round(value * 100)}%`
})
</script>

<style scoped>
.assessment-result {
  display: grid;
  gap: var(--vt-space-4);
  padding: var(--vt-space-4);
}

.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-3);
}

.panel-heading strong {
  font-size: var(--vt-text-sm);
  color: var(--vt-accent-teal);
}

.result-body {
  display: grid;
  gap: var(--vt-space-3);
}

.metric-row {
  display: grid;
  gap: 2px;
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
}

.metric-row span {
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
}

.metric-row strong {
  font-size: var(--vt-text-sm);
  color: var(--vt-text-primary);
  line-height: 1.5;
}

.feedback {
  margin: 0;
  font-size: var(--vt-text-sm);
  color: var(--vt-text-secondary);
  line-height: 1.65;
}
</style>
