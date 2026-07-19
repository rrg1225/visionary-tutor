<template>
  <section class="resource-drawer-preview vt-card" aria-label="资源库预览">
    <header class="preview-head">
      <span class="vt-eyebrow">资源干预</span>
      <h3>生成内容已迁至资源库</h3>
      <p class="preview-copy">
        讲义、题库、本地演示动画与 Agent 协作轨迹在独立页面展示，避免挤占对话侧边栏。
      </p>
    </header>

    <div v-if="isGenerating" class="preview-progress" aria-live="polite">
      <div class="progress-track generation">
        <span :style="{ width: `${normalizedProgress(generationProgress)}%` }"></span>
      </div>
      <p>{{ generationStatus || '资源生成中…' }}</p>
    </div>

    <ul v-if="recentResources.length" class="preview-list">
      <li v-for="item in recentResources" :key="item.id || item.artifactType">
        <span class="preview-type">{{ item.type }}</span>
        <strong>{{ item.title }}</strong>
        <span class="preview-summary">{{ item.summary }}</span>
      </li>
    </ul>
    <p v-else class="preview-empty">暂无已生成资源。前往资源库选择主题与类型后生成。</p>

    <div class="preview-actions">
      <RouterLink class="vt-btn vt-btn-primary" to="/resources">
        打开我的资源库
      </RouterLink>
      <RouterLink
        v-if="recentResources.length"
        class="vt-btn vt-btn-outline"
        :to="{ path: '/resources', query: { focus: 'fresh' } }"
      >
        查看刚刚生成
      </RouterLink>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

const props = defineProps({
  resources: { type: Array, default: () => [] },
  isGenerating: { type: Boolean, default: false },
  generationProgress: { type: Number, default: 0 },
  generationStatus: { type: String, default: '' },
})

const recentResources = computed(() => props.resources.slice(0, 3))

function normalizedProgress(progress) {
  const value = Number(progress || 0)
  return Math.max(8, Math.min(100, value))
}
</script>

<style scoped>
.resource-drawer-preview {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-3);
}

.preview-head h3 {
  margin: var(--vt-space-1) 0 0;
  font-size: var(--vt-text-sm);
}

.preview-copy,
.preview-empty {
  margin: 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
  line-height: 1.55;
}

.preview-list {
  display: grid;
  gap: var(--vt-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.preview-list li {
  display: grid;
  gap: 2px;
  padding: var(--vt-space-2);
  border-radius: var(--vt-radius-sm);
  border: 1px solid var(--vt-border-light);
  background: var(--vt-bg-primary);
}

.preview-type {
  font-size: 10px;
  font-weight: var(--vt-font-semibold);
  color: var(--vt-accent-teal, #0d9488);
}

.preview-list strong {
  font-size: var(--vt-text-xs);
}

.preview-summary {
  font-size: 10px;
  color: var(--vt-text-tertiary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}

.preview-progress {
  display: grid;
  gap: var(--vt-space-1);
}

.preview-progress p {
  margin: 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
}

.progress-track.generation {
  height: 6px;
  background: var(--vt-border-light);
  border-radius: 999px;
  overflow: hidden;
}

.progress-track.generation span {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #3b82f6, #60a5fa);
}
</style>
