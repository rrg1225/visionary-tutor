import { recordLearningMetric } from '../api/resources'
import { useAuthStore } from '../stores/authStore'
import { useLearningSessionStore } from '../stores/learningSession'

export function useLearningMetrics() {
  async function record(type, payload = {}) {
    const authStore = useAuthStore()
    const learningSession = useLearningSessionStore()
    if (!authStore.isRegistered) return

    const userId = Number(authStore.currentUserId)
    if (!Number.isFinite(userId)) return

    await learningSession.ensureCurrentSession(payload.topic || '学习会话').catch(() => null)

    try {
      await recordLearningMetric({
        userId,
        sessionId: learningSession.currentSessionId,
        type,
        concept: payload.concept || null,
        numericValue: payload.numericValue ?? null,
        textValue: payload.textValue || null,
        source: payload.source || 'frontend',
      })
    } catch (error) {
      console.warn('[Metrics] record skipped:', error?.message || error)
    }
  }

  return { record }
}
