<template>
  <div
    v-if="active || retryable"
    class="generation-progress"
    aria-live="polite"
    data-testid="skeleton-resource-generation"
  >
    <div class="progress-track generation" aria-label="资源生成进度">
      <span :style="{ width: `${normalized}%` }" />
    </div>
    <p class="generation-status" :data-status="status">
      {{ message || "多智能体协作生成中..." }}
    </p>
    <p v-if="active && etaSeconds > 0" class="generation-eta">
      预计还需约 {{ formattedEta }}；任务会在后台继续，离开本页不会中断。
    </p>
    <div class="generation-actions">
      <button
        v-if="active"
        type="button"
        class="vt-btn vt-btn-outline vt-btn-sm"
        @click="emit('cancel')"
      >
        取消生成
      </button>
      <button
        v-if="retryable && !active"
        type="button"
        class="vt-btn vt-btn-primary vt-btn-sm"
        @click="emit('retry')"
      >
        重试本次生成
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue";
import {
  inferGenerationStatus,
  normalizeProgress,
} from "../../domain/resource-status";

const props = withDefaults(
  defineProps<{
    active: boolean;
    progress?: number;
    message?: string;
    etaSeconds?: number;
    retryable?: boolean;
  }>(),
  {
    progress: 0,
    message: "",
    etaSeconds: 0,
    retryable: false,
  },
);
const emit = defineEmits<{ cancel: []; retry: [] }>();

const normalized = computed(() => normalizeProgress(props.progress));
const status = computed(() =>
  inferGenerationStatus(props.active, props.progress, props.message),
);
const formattedEta = computed(() => {
  const seconds = Math.max(0, Number(props.etaSeconds || 0));
  if (seconds < 60) return `${Math.max(5, Math.ceil(seconds / 5) * 5)} 秒`;
  return `${Math.ceil(seconds / 60)} 分钟`;
});
</script>

<style scoped>
.generation-progress {
  background: rgba(59, 130, 246, 0.06);
  border-radius: var(--vt-radius-md);
  display: grid;
  gap: var(--vt-space-2);
  padding: var(--vt-space-3);
}
.generation-status {
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
  margin: 0;
}
.generation-eta {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
  margin: 0;
}
.generation-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}
.progress-track.generation {
  background: var(--vt-border-light);
  border-radius: 999px;
  height: 8px;
  overflow: hidden;
}
.progress-track.generation span {
  background: linear-gradient(90deg, #3b82f6, #60a5fa);
  display: block;
  height: 100%;
  transition: width 0.35s ease;
}
</style>
