<template>
  <PanelCard class="profile-panel" aria-label="动态学习画像">
    <header class="panel-heading">
      <span class="vt-eyebrow">学情画像 · 感知</span>
      <strong>7 维动态状态画像</strong>
      <span v-if="profileVersion" class="version-tag">
        v{{ profileVersion }} · 路径 v{{ pathVersion || 1 }}
        <span v-if="profileSource === 'backend'" class="source-tag">后端权威</span>
      </span>
    </header>

    <div v-if="extractionStatus === 'RUNNING'" class="profile-extract-progress" role="status" aria-live="polite">
      <span class="pulse-dot" aria-hidden="true"></span>
      <div class="profile-extract-copy">
        <strong>AI 正在更新画像</strong>
        <p>正在分析你的回答并抽取 7 维学习特征，请稍候…</p>
      </div>
    </div>
    <p v-else-if="extractionMessage" class="profile-status">{{ extractionMessage }}</p>

    <p v-if="changedDimensions.length" class="profile-changes">
      本次更新：{{ changedDimensions.join('、') }}
    </p>

    <div class="dimension-list">
      <article
        v-for="item in dimensions"
        :key="item.key"
        class="dimension-row"
        :class="{ changed: changedDimensions.includes(item.label) }"
      >
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </article>
    </div>

    <p v-if="updatedAtText" class="profile-update">随学更新：{{ updatedAtText }}</p>
  </PanelCard>
</template>

<script setup>
import { computed } from 'vue'
import PanelCard from './common/PanelCard.vue'

const props = defineProps({
  dimensions: {
    type: Array,
    default: () => [],
  },
  updatedAt: {
    type: String,
    default: '',
  },
  extractionStatus: {
    type: String,
    default: 'IDLE',
  },
  extractionMessage: {
    type: String,
    default: '',
  },
  changedDimensions: {
    type: Array,
    default: () => [],
  },
  profileVersion: {
    type: Number,
    default: 0,
  },
  pathVersion: {
    type: Number,
    default: 0,
  },
  profileSource: {
    type: String,
    default: 'local',
  },
})

const updatedAtText = computed(() => {
  if (!props.updatedAt) return ''
  return new Date(props.updatedAt).toLocaleString()
})
</script>

<style scoped>
.profile-panel {
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
  color: var(--vt-text-primary);
}

.version-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--vt-space-1);
  font-size: 10px;
  color: var(--vt-text-tertiary);
  white-space: nowrap;
}

.source-tag {
  padding: 0 var(--vt-space-1);
  border-radius: var(--vt-radius-full);
  background: rgba(13, 148, 136, 0.12);
  color: var(--vt-accent-teal-dark);
}

.profile-status {
  margin: 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
}

.profile-extract-progress {
  display: flex;
  align-items: flex-start;
  gap: var(--vt-space-3);
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: rgba(59, 130, 246, 0.08);
  border: 1px solid rgba(59, 130, 246, 0.2);
}

.profile-extract-copy {
  display: grid;
  gap: 2px;
}

.profile-extract-copy strong {
  font-size: var(--vt-text-sm);
  color: var(--vt-accent-primary);
}

.profile-extract-copy p {
  margin: 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
}

.pulse-dot {
  width: 10px;
  height: 10px;
  margin-top: 4px;
  border-radius: 50%;
  background: var(--vt-accent-primary);
  flex-shrink: 0;
  animation: profile-pulse 1.2s ease-in-out infinite;
}

@keyframes profile-pulse {
  0%, 100% { transform: scale(1); opacity: 0.55; }
  50% { transform: scale(1.35); opacity: 1; }
}

.profile-changes {
  margin: 0;
  padding: var(--vt-space-2) var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: rgba(59, 130, 246, 0.08);
  color: var(--vt-accent-primary);
  font-size: var(--vt-text-xs);
}

.dimension-list {
  display: grid;
  gap: var(--vt-space-2);
}

.dimension-row {
  display: grid;
  gap: 2px;
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
}

.dimension-row.changed {
  border: 1px solid rgba(59, 130, 246, 0.35);
  background: rgba(59, 130, 246, 0.06);
}

.dimension-row span {
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
}

.dimension-row strong {
  font-size: var(--vt-text-sm);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-primary);
  line-height: 1.45;
}

.profile-update {
  margin: 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
}
</style>
