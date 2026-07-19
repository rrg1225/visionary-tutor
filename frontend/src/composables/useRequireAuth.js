import { computed } from 'vue'
import { useAuthStore } from '../stores/authStore.js'

/**
 * useRequireAuth - 鉴权守卫 Composable
 *
 * 游客对话次数由后端 Redis 统一计数；前端在发送前同步配额并拦截。
 */

const DEEP_ACTIONS = [
  'submitAssessment',
  'viewDiagnostic',
  'uploadFile',
  'saveProfile',
  'exportReport',
]

const CONVERSATION_TURN_THRESHOLD = 3

export function useRequireAuth() {
  const authStore = useAuthStore()

  function shouldRequireLogin(actionType, usedTurns = authStore.conversationTurns) {
    if (authStore.isRegistered) {
      return false
    }

    if (!authStore.isLoggedIn) {
      return true
    }

    if (authStore.isGuest) {
      if (DEEP_ACTIONS.includes(actionType)) {
        return true
      }

      if (actionType === 'sendMessage') {
        return usedTurns >= CONVERSATION_TURN_THRESHOLD
      }
    }

    return false
  }

  async function requireAuth(actionType, payload = null, options = {}) {
    const { mode = 'register' } = options

    if (authStore.isRegistered) {
      return true
    }

    let usedTurns = authStore.conversationTurns

    if (authStore.isGuest && actionType === 'sendMessage') {
      const quota = await authStore.syncGuestChatQuota()
      if (quota) {
        usedTurns = quota.usedTurns
      }
    }

    if (!shouldRequireLogin(actionType, usedTurns)) {
      return true
    }

    const pendingAction = {
      type: actionType,
      payload,
      timestamp: Date.now(),
    }

    authStore.openAuthModal(mode, pendingAction)
    return false
  }

  async function checkAndIncrementTurns() {
    if (!authStore.isGuest) {
      return false
    }
    const quota = await authStore.syncGuestChatQuota()
    return (quota?.usedTurns ?? authStore.conversationTurns) >= CONVERSATION_TURN_THRESHOLD
  }

  const conversationStatus = computed(() => {
    const turns = authStore.conversationTurns
    return {
      turns,
      threshold: CONVERSATION_TURN_THRESHOLD,
      shouldPrompt: authStore.shouldPromptLogin,
      remaining: Math.max(0, CONVERSATION_TURN_THRESHOLD - turns),
    }
  })

  const turnsUntilLoginRequired = computed(() => {
    if (!authStore.isGuest) return null
    return Math.max(0, CONVERSATION_TURN_THRESHOLD - authStore.conversationTurns)
  })

  function resetTurns() {
    authStore.resetConversationTurns()
  }

  function promptLoginNow(options = {}) {
    const { mode = 'register', payload = null } = options
    authStore.openAuthModal(mode, payload)
  }

  return {
    requireAuth,
    checkAndIncrementTurns,
    resetTurns,
    promptLoginNow,
    conversationStatus,
    turnsUntilLoginRequired,
    shouldRequireLogin,
    DEEP_ACTIONS,
    CONVERSATION_TURN_THRESHOLD,
  }
}
