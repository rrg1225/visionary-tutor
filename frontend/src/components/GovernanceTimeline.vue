<template>
  <div class="governance-timeline" data-testid="governance-timeline">
    <el-empty
      v-if="!hasEvents"
      description="暂无治理轨迹数据"
      :image-size="80"
    >
      <template #description>
        <p class="empty-desc">当前产物尚未产生 Critic 返修记录，或治理轨迹尚未同步。</p>
      </template>
    </el-empty>

    <template v-else>
      <header v-if="artifactLabel" class="timeline-header">
        <span class="timeline-title">双层编排治理轨迹</span>
        <span class="timeline-meta">{{ artifactLabel }}</span>
      </header>

      <el-timeline class="governance-timeline-list">
        <el-timeline-item
          v-for="(event, index) in normalizedEvents"
          :key="timelineKey(event, index)"
          :type="timelineItemType(event.decision)"
          :timestamp="roundLabel(event)"
          placement="top"
        >
          <div class="event-card">
            <div class="event-tags">
              <el-tag size="small" effect="plain">
                Score {{ formatScore(event.compositeScore) }}
              </el-tag>
              <el-tag
                size="small"
                :type="deltaTagType(event.scoreDelta)"
                effect="light"
              >
                Δ {{ formatDelta(event.scoreDelta) }}
              </el-tag>
              <el-tag
                v-if="event.decision"
                size="small"
                :type="decisionTagType(event.decision)"
                effect="dark"
              >
                {{ event.decision }}
              </el-tag>
            </div>

            <p v-if="event.criticFeedback" class="event-feedback">
              <span class="field-label">Critic 反馈</span>
              {{ event.criticFeedback }}
            </p>

            <p v-if="showHaltReason(event)" class="event-halt-reason">
              <span class="field-label">熔断原因</span>
              {{ resolveHaltReason(event) }}
            </p>
          </div>
        </el-timeline-item>
      </el-timeline>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ElTimeline, ElTimelineItem, ElTag, ElEmpty } from 'element-plus'
import 'element-plus/es/components/timeline/style/css'
import 'element-plus/es/components/timeline-item/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/empty/style/css'

const props = defineProps({
  /** 对应后端 GovernanceTraceDto：{ artifactId, events | revisions } */
  traceData: {
    type: Object,
    default: null,
  },
})

const HALT_REASON_LABELS = {
  HALT_MAX_ROUND: '已达最大返修轮次上限，强制熔断以防 Agent 死循环',
  HALT_NEGATIVE_DELTA: '得分增益 Δ ≤ 0，返修产生负优化或停滞',
  HALT_LOW_MARGINAL_UTILITY: '得分增益低于边际阈值，继续返修边际效用不足',
}

/** 兼容后端 revisions 与前端 events 两种字段名 */
const normalizedEvents = computed(() => {
  const data = props.traceData
  if (!data || typeof data !== 'object') {
    return []
  }
  const list = data.events ?? data.revisions
  return Array.isArray(list) ? list : []
})

const hasEvents = computed(() => normalizedEvents.value.length > 0)

const artifactLabel = computed(() => {
  const id = props.traceData?.artifactId
  if (id === null || id === undefined || id === '') {
    return ''
  }
  return `产物 #${id}`
})

function timelineKey(event, index) {
  const round = event?.revisionRound
  return round !== undefined && round !== null ? `round-${round}` : `event-${index}`
}

function roundLabel(event) {
  const round = event?.revisionRound
  if (round === undefined || round === null) {
    return '未知轮次'
  }
  return `第 ${round} 轮返修`
}

function isHaltDecision(decision) {
  if (!decision || typeof decision !== 'string') {
    return false
  }
  return decision.toUpperCase().includes('HALT')
}

function timelineItemType(decision) {
  if (isHaltDecision(decision)) {
    return 'danger'
  }
  if (decision === 'CONTINUE') {
    return 'success'
  }
  return 'primary'
}

function decisionTagType(decision) {
  if (isHaltDecision(decision)) {
    return 'danger'
  }
  if (decision === 'CONTINUE') {
    return 'success'
  }
  return 'info'
}

function deltaTagType(delta) {
  const value = Number(delta)
  if (Number.isNaN(value)) {
    return 'info'
  }
  if (value > 0) {
    return 'success'
  }
  if (value < 0) {
    return 'danger'
  }
  return 'warning'
}

function formatScore(score) {
  const value = Number(score)
  if (Number.isNaN(value)) {
    return '—'
  }
  return value.toFixed(1)
}

function formatDelta(delta) {
  const value = Number(delta)
  if (Number.isNaN(value)) {
    return '—'
  }
  if (value > 0) {
    return `+${value.toFixed(1)}`
  }
  return value.toFixed(1)
}

function showHaltReason(event) {
  if (!event || !isHaltDecision(event.decision)) {
    return false
  }
  return Boolean(event.reason || HALT_REASON_LABELS[event.decision] || event.decision)
}

function resolveHaltReason(event) {
  if (event?.reason) {
    return event.reason
  }
  return HALT_REASON_LABELS[event?.decision] ?? `熔断决策：${event?.decision ?? '未知'}`
}
</script>

<style scoped>
.governance-timeline {
  width: 100%;
  padding: var(--vt-space-4);
}

.empty-desc {
  margin: 0;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
  line-height: 1.5;
}

.timeline-header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--vt-space-2);
  margin-bottom: var(--vt-space-4);
  padding-bottom: var(--vt-space-3);
  border-bottom: 1px solid var(--vt-border-light);
}

.timeline-title {
  font-size: var(--vt-text-base);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-primary);
}

.timeline-meta {
  font-size: var(--vt-text-sm);
  color: var(--vt-text-tertiary);
  font-family: var(--vt-font-mono);
}

.governance-timeline-list {
  padding-left: var(--vt-space-1);
}

.event-card {
  display: grid;
  gap: var(--vt-space-3);
}

.event-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}

.field-label {
  display: block;
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: var(--vt-space-1);
}

.event-feedback,
.event-halt-reason {
  margin: 0;
  font-size: var(--vt-text-sm);
  line-height: 1.6;
  color: var(--vt-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.event-halt-reason {
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: color-mix(in srgb, var(--vt-accent-red) 6%, var(--vt-surface));
  border: 1px solid color-mix(in srgb, var(--vt-accent-red) 18%, transparent);
  color: var(--vt-text-primary);
}
</style>
