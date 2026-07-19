import { ref, computed, onMounted } from 'vue'
import { useAuthStore } from '../stores/authStore'
import { useLearningSessionStore } from '../stores/learningSession'
import { useUserProfileStore } from '../stores/userProfile'
import {
  assessLearning,
  fetchKnowledgeTracingRadar,
  recordLearningMetric,
} from '../api/resources'
import demoLearningReport from '../data/demoLearningReport.json'

function normalizeTracingScore(raw) {
  const value = Number(raw ?? 0)
  if (!Number.isFinite(value)) return 0
  return Math.round(value <= 1 ? value * 100 : value)
}

function mapTracingPayload(payload) {
  const concepts = Array.isArray(payload?.concepts) ? payload.concepts : []
  const radar = concepts.map((item) => ({
    axis: String(item?.concept ?? item?.knowledgeConcept ?? '未知概念'),
    value: normalizeTracingScore(item?.score ?? item?.confidenceScore),
  }))

  return {
    radar,
    insufficientData: Boolean(payload?.insufficientData) || radar.length < 2,
    meaningfulMetricCount: Number(payload?.meaningfulCount ?? radar.length),
    hasTracingRows: radar.length > 0,
  }
}

export function useLearningReport() {
  const authStore = useAuthStore()
  const learningSession = useLearningSessionStore()
  const userProfile = useUserProfileStore()

  const loading = ref(false)
  const radarLoading = ref(false)
  const error = ref('')
  const loginRequired = ref(false)
  const summary = ref('')
  const metricsSummary = ref('')
  const suggestions = ref([])
  const radarData = ref([])
  const shouldReplan = ref(false)
  const replanReason = ref('')
  const llmUsed = ref(false)
  const insufficientData = ref(false)
  const meaningfulMetricCount = ref(0)
  const isDemoMode = ref(false)
  const prePostSummary = ref(null)
  const masteryCurve = ref([])

  const prePostComparable = computed(() => Boolean(prePostSummary.value?.comparable))

  const masteryCurvePoints = computed(() => {
    if (!masteryCurve.value.length) return []
    const width = 320
    const height = 140
    const padding = 24
    const sorted = [...masteryCurve.value].sort(
      (a, b) => new Date(a.eventTime).getTime() - new Date(b.eventTime).getTime(),
    )
    const maxX = Math.max(sorted.length - 1, 1)
    return sorted.map((point, index) => {
      const x = padding + ((width - padding * 2) * index) / maxX
      const y = height - padding - ((Math.max(0, Math.min(100, point.masteryPercent)) / 100) * (height - padding * 2))
      return { ...point, x, y, label: formatCurveLabel(point) }
    })
  })

  const masteryPolyline = computed(() =>
    masteryCurvePoints.value.map((p) => `${p.x},${p.y}`).join(' '),
  )

  function formatCurveLabel(point) {
    const type = point.metricType || ''
    if (type === 'PRE_TEST') return '前测'
    if (type === 'POST_TEST') return '后测'
    if (type === 'MASTERY_DELTA') return '掌握度变化'
    if (type === 'PATH_STEP_COMPLETED') return '路径步骤'
    return '练习'
  }

  const radarPoints = computed(() => {
    const count = radarData.value.length || 1
    const center = 120
    const radius = 88
    return radarData.value.map((point, index) => {
      const angle = (Math.PI * 2 * index) / count - Math.PI / 2
      const r = (Math.max(0, Math.min(100, point.value)) / 100) * radius
      return {
        ...point,
        x: center + Math.cos(angle) * r,
        y: center + Math.sin(angle) * r,
        labelX: center + Math.cos(angle) * (radius + 22),
        labelY: center + Math.sin(angle) * (radius + 22),
      }
    })
  })

  const polygonPoints = computed(() => radarPoints.value.map((p) => `${p.x},${p.y}`).join(' '))

  function applyReportDetail(detail = {}, meta = {}) {
    summary.value = meta.summary || ''
    metricsSummary.value = meta.metricsSummary || ''
    llmUsed.value = Boolean(meta.llmUsed)
    insufficientData.value = Boolean(detail.insufficientData)
    meaningfulMetricCount.value = Number(detail.meaningfulMetricCount ?? 0)
    radarData.value = Array.isArray(detail.radarData)
      ? detail.radarData.map((item) => ({
          axis: item.axis,
          value: Number(item.value ?? 0),
        }))
      : []
    suggestions.value = detail.suggestions || []
    shouldReplan.value = Boolean(detail.shouldReplan)
    replanReason.value = detail.replanReason || ''
    prePostSummary.value = detail.prePostSummary || null
    masteryCurve.value = Array.isArray(detail.masteryCurve)
      ? detail.masteryCurve.map((point) => ({
          concept: point.concept || '综合',
          metricType: point.metricType || '',
          masteryPercent: Number(point.masteryPercent ?? 0),
          eventTime: point.eventTime || '',
        }))
      : []
  }

  function loadDemoReport() {
    isDemoMode.value = true
    loading.value = false
    radarLoading.value = false
    error.value = ''
    loginRequired.value = false
    applyReportDetail(demoLearningReport, {
      summary: demoLearningReport.summary,
      metricsSummary: demoLearningReport.metricsSummary,
      llmUsed: demoLearningReport.llmUsed,
    })
    prePostSummary.value = demoLearningReport.prePostSummary || null
    masteryCurve.value = demoLearningReport.masteryCurve || []
  }

  async function loadKnowledgeTracingRadar(userId) {
    radarLoading.value = true
    try {
      return await fetchKnowledgeTracingRadar(userId)
    } catch (tracingError) {
      console.warn('[LearningReport] knowledge tracing radar skipped:', tracingError?.message || tracingError)
      return null
    } finally {
      radarLoading.value = false
    }
  }

  async function loadAssessment() {
    isDemoMode.value = false
    error.value = ''
    loginRequired.value = false
    if (!authStore.isRegistered) {
      loginRequired.value = true
      return
    }
    const userId = Number(authStore.currentUserId)
    if (!Number.isFinite(userId)) {
      error.value = '无法识别当前用户。'
      return
    }

    loading.value = true
    try {
      await learningSession.ensureCurrentSession(userProfile.goal || '学习效果评估')
      await recordLearningMetric({
        userId,
        sessionId: learningSession.currentSessionId,
        type: 'REPORT_VIEW',
        textValue: 'learning_report_opened',
        source: 'learning_report',
      }).catch(() => null)

      const [result, tracingPayload] = await Promise.all([
        assessLearning(userId, learningSession.currentSessionId),
        loadKnowledgeTracingRadar(userId),
      ])

      applyReportDetail(result.detail || {}, {
        summary: result.summary || '',
        metricsSummary: result.metricsSummary || '',
        llmUsed: result.llmUsed,
      })

      if (tracingPayload) {
        const mapped = mapTracingPayload(tracingPayload)
        if (mapped.hasTracingRows) {
          radarData.value = mapped.radar
          insufficientData.value = mapped.insufficientData
          meaningfulMetricCount.value = mapped.meaningfulMetricCount
        }
      }
    } catch (err) {
      error.value = err?.message || '学习效果评估请求失败'
    } finally {
      loading.value = false
    }
  }

  onMounted(() => {
    userProfile.hydrateFromStorage()
    loadAssessment()
  })

  return {
    loading,
    radarLoading,
    error,
    loginRequired,
    summary,
    metricsSummary,
    suggestions,
    radarData,
    radarPoints,
    polygonPoints,
    shouldReplan,
    replanReason,
    llmUsed,
    insufficientData,
    meaningfulMetricCount,
    isDemoMode,
    prePostSummary,
    prePostComparable,
    masteryCurve,
    masteryCurvePoints,
    masteryPolyline,
    loadAssessment,
    loadDemoReport,
  }
}
