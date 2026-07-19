<template>
  <div v-if="show" class="resource-actions">
    <button
      v-if="canReadAloud"
      type="button"
      class="vt-btn vt-btn-ghost vt-btn-sm"
      @click="emit('read-aloud')"
    >
      {{ ttsSpeaking ? "朗读中…" : "朗读" }}
    </button>
    <button
      v-if="canExportPptx"
      type="button"
      class="vt-btn vt-btn-outline vt-btn-sm"
      :disabled="pptxLoading"
      @click="emit('export-pptx')"
    >
      {{ pptxLoading ? "导出中…" : "导出 PPTX" }}
    </button>
    <button
      v-if="showVisualization"
      type="button"
      class="vt-btn vt-btn-ghost vt-btn-sm"
      :disabled="vizLoading"
      @click="emit('open-visualization')"
    >
      {{ vizLoading ? "加载中…" : "运行可视化" }}
    </button>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    show?: boolean;
    canReadAloud?: boolean;
    ttsSpeaking?: boolean;
    canExportPptx?: boolean;
    pptxLoading?: boolean;
    showVisualization?: boolean;
    vizLoading?: boolean;
  }>(),
  {
    show: true,
    canReadAloud: false,
    ttsSpeaking: false,
    canExportPptx: false,
    pptxLoading: false,
    showVisualization: false,
    vizLoading: false,
  },
);

const emit = defineEmits<{
  "read-aloud": [];
  "export-pptx": [];
  "open-visualization": [];
}>();
</script>

<style scoped>
.resource-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}
</style>
