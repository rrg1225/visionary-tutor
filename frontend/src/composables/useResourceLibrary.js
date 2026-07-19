import { ref } from 'vue'
import { listLearningSessions } from '../api/learning'
import { listGeneratedResources, listShowcaseResources } from '../api/resources'
import { mapResourceToCard } from '../stores/learningSession'

const MAX_SESSIONS = 40
function mergeShowcaseResources(personal, showcaseRaw) {
  const personalList = Array.isArray(personal) ? personal : []
  const showcaseList = (Array.isArray(showcaseRaw) ? showcaseRaw : [])
    .map((item) => mapResourceToCard(item, {
      sessionTopic: item.sessionTopic || '【示例资源库】CNN 专题',
      learningSessionId: item.learningSessionId,
      isShowcase: true,
    }))

  // Demo resources are a separate catalog, never fillers masquerading as the
  // current learner's generated output.
  return [...personalList, ...showcaseList]
}

export function useResourceLibrary() {
  const libraryResources = ref([])
  const loading = ref(false)
  const loadError = ref('')
  const sessionCount = ref(0)
  const hasShowcaseFill = ref(false)

  async function loadLibrary(userId) {
    if (!userId) {
      libraryResources.value = []
      loadError.value = ''
      sessionCount.value = 0
      hasShowcaseFill.value = false
      return []
    }

    loading.value = true
    loadError.value = ''
    try {
      const [sessionsResult, showcaseResult] = await Promise.allSettled([
        listLearningSessions(userId, { silent: true }),
        listShowcaseResources({ silent: true }),
      ])
      const sessions = sessionsResult.status === 'fulfilled' ? sessionsResult.value : []
      const showcaseRaw = showcaseResult.status === 'fulfilled' ? showcaseResult.value : []
      if (sessionsResult.status === 'rejected') {
        loadError.value = '个人资源同步失败，当前仅显示示例资源。请刷新登录状态后重试。'
      }
      const recentSessions = (Array.isArray(sessions) ? sessions : []).slice(0, MAX_SESSIONS)
      sessionCount.value = recentSessions.length

      const batchResults = await Promise.allSettled(
        recentSessions.map(async (session) => {
          const raw = await listGeneratedResources(session.id, { silent: true })
          return (Array.isArray(raw) ? raw : []).map((item) => mapResourceToCard(item, {
            sessionTopic: session.topic,
            learningSessionId: session.id,
          }))
        }),
      )
      const batches = batchResults
        .filter((result) => result.status === 'fulfilled')
        .map((result) => result.value)
      if (batchResults.some((result) => result.status === 'rejected')) {
        loadError.value = '部分个人资源同步失败，请点击重试；示例资源不计入你的生成记录。'
      }

      const merged = new Map()
      for (const batch of batches) {
        for (const card of batch) {
          const key = card.id != null
            ? `id:${card.id}`
            : `${card.learningSessionId}-${card.artifactType}-${card.runId}`
          merged.set(key, card)
        }
      }

      const personal = [...merged.values()].sort((a, b) => (b.id || 0) - (a.id || 0))
      const withShowcase = mergeShowcaseResources(personal, showcaseRaw)
      hasShowcaseFill.value = withShowcase.some((item) => item.isShowcase)
      libraryResources.value = withShowcase
      return libraryResources.value
    } finally {
      loading.value = false
    }
  }

  return {
    libraryResources,
    loading,
    loadError,
    sessionCount,
    hasShowcaseFill,
    loadLibrary,
  }
}
