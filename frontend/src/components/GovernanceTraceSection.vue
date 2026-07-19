<template>
  <details v-if="hasArtifactId" class="governance-trace-section" :open="hasTraceEvents">
    <summary>双层编排治理轨迹</summary>
    <div
      v-loading="traceLoading"
      class="governance-trace-host"
      element-loading-text="加载治理轨迹…"
    >
      <GovernanceTimeline :trace-data="traceData" />
    </div>
  </details>
</template>

<script setup>
import { computed, defineAsyncComponent, ref, watch } from 'vue'
import { ElLoading } from 'element-plus'
import 'element-plus/es/components/loading/style/css'
import { fetchGovernanceTrace } from '../api/resources'

const GovernanceTimeline = defineAsyncComponent(() => import('./GovernanceTimeline.vue'))

const vLoading = ElLoading.directive

const props = defineProps({
  artifactId: {
    type: [Number, String],
    default: null,
  },
})

const traceData = ref(null)
const traceLoading = ref(false)

const hasArtifactId = computed(() => {
  const id = props.artifactId
  return id !== null && id !== undefined && String(id).trim() !== ''
})

const hasTraceEvents = computed(() => {
  const data = traceData.value
  if (!data || typeof data !== 'object') {
    return false
  }
  const list = data.revisions ?? data.events
  return Array.isArray(list) && list.length > 0
})

watch(
  () => props.artifactId,
  async (artifactId, _previous, onCleanup) => {
    if (!artifactId && artifactId !== 0) {
      traceData.value = null
      return
    }

    let cancelled = false
    onCleanup(() => {
      cancelled = true
    })

    traceLoading.value = true
    try {
      const data = await fetchGovernanceTrace(artifactId)
      if (!cancelled) {
        traceData.value = data ?? { artifactId, revisions: [] }
      }
    } catch (err) {
      if (!cancelled) {
        traceData.value = { artifactId, revisions: [] }
        console.warn('[GovernanceTraceSection] fetch failed:', err?.message || err)
      }
    } finally {
      if (!cancelled) {
        traceLoading.value = false
      }
    }
  },
  { immediate: true },
)
</script>

<style scoped>
.governance-trace-section {
  margin-top: var(--vt-space-2);
  border-radius: var(--vt-radius-md);
  border: 1px solid var(--vt-border-light);
  background: var(--vt-bg-primary);
}

.governance-trace-section summary {
  cursor: pointer;
  padding: var(--vt-space-2) var(--vt-space-3);
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-secondary);
  list-style: none;
}

.governance-trace-section summary::-webkit-details-marker {
  display: none;
}

.governance-trace-host {
  min-height: 72px;
  border-top: 1px solid var(--vt-border-light);
}
</style>
