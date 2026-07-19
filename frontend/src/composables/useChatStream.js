import { proxyRefs, ref } from 'vue'
import { appendSessionChatMessage, listSessionChatMessages } from '../api/learning'
import { streamChatSse } from '../api/stream'
import { useRequireAuth } from './useRequireAuth'
import { useAuthStore } from '../stores/authStore'
import { useLearningSessionStore } from '../stores/learningSession'
import { useUserProfileStore } from '../stores/userProfile'

import { useLearningMetrics } from './useLearningMetrics'
import { useTextToSpeech } from './useTextToSpeech'
import { serializeProfileSnapshot } from '../utils/profileSnapshot'
import { sanitizeAssistantContent } from '../utils/sanitizeAssistantContent'

const GUEST_CHAT_PREFIX = 'vt_guest_chat_'

function guestStorageKey(guestId) {
  return `${GUEST_CHAT_PREFIX}${guestId || 'anonymous'}`
}

function mapServerMessages(rows = []) {
  return rows.map((row) => ({
    id: row.id ? `srv-${row.id}` : `${Date.now()}-${Math.random()}`,
    role: row.role,
    content: row.content,
  }))
}

export function useChatStream() {
  const messages = ref([])
  const inputMessage = ref('')
  const isStreaming = ref(false)
  const currentStreamContent = ref('')
  const streamStatusText = ref('')
  const hasReceivedFirstToken = ref(false)
  const ragCitations = ref([])
  const ragGrounded = ref(false)
  const ragStatus = ref('')
  const knowledgeBaseDisconnected = ref(false)
  const knowledgeBaseHaMode = ref(false)
  const groundingWarning = ref('')
  const agentTrace = ref(null)
  const historyLoading = ref(false)
  const tutoringMode = ref(localStorage.getItem('vt_tutoring_mode') || 'AUTO')
  const streamElapsedSeconds = ref(0)

  let abortController = null
  let progressTimer = null

  const { requireAuth } = useRequireAuth()
  const authStore = useAuthStore()
  const learningSession = useLearningSessionStore()
  const userProfile = useUserProfileStore()
  const metrics = useLearningMetrics()
  const tts = useTextToSpeech()

  function onFirstToken() {
    if (hasReceivedFirstToken.value) return
    hasReceivedFirstToken.value = true
    streamStatusText.value = '正在输出答案…'
  }

  function startProgressTimer() {
    const startedAt = Date.now()
    streamElapsedSeconds.value = 0
    if (progressTimer) window.clearInterval(progressTimer)
    progressTimer = window.setInterval(() => {
      streamElapsedSeconds.value = Math.floor((Date.now() - startedAt) / 1000)
      if (hasReceivedFirstToken.value) return
      if (streamElapsedSeconds.value < 4) streamStatusText.value = '正在理解你的问题…'
      else if (streamElapsedSeconds.value < 10) streamStatusText.value = '正在检索相关学习资料…'
      else if (streamElapsedSeconds.value < 20) streamStatusText.value = '正在组织个性化答案…'
      else streamStatusText.value = `准备时间较长（${streamElapsedSeconds.value} 秒），你可以继续等待或停止生成。`
    }, 1000)
  }

  function stopProgressTimer() {
    if (progressTimer) window.clearInterval(progressTimer)
    progressTimer = null
  }

  function addMessage(role, content, extra = {}) {
    messages.value.push({
      id: `${Date.now()}-${Math.random()}`,
      role,
      content,
      ...extra,
    })
    persistGuestMessages()
  }

  function clearMessages() {
    messages.value = []
    persistGuestMessages()
  }

  function setMessages(nextMessages = []) {
    messages.value = Array.isArray(nextMessages) ? [...nextMessages] : []
    persistGuestMessages()
  }

  function persistGuestMessages() {
    if (!authStore.isGuest || !authStore.guestId) return
    try {
      localStorage.setItem(guestStorageKey(authStore.guestId), JSON.stringify(messages.value))
    } catch {
      // ignore quota errors
    }
  }

  function loadGuestMessages() {
    if (!authStore.isGuest || !authStore.guestId) {
      messages.value = []
      return
    }
    try {
      const raw = localStorage.getItem(guestStorageKey(authStore.guestId))
      const parsed = raw ? JSON.parse(raw) : []
      messages.value = Array.isArray(parsed) ? parsed : []
    } catch {
      messages.value = []
    }
  }

  async function hydrateFromSession(sessionId) {
    if (!sessionId || !authStore.isRegistered) {
      clearMessages()
      return
    }
    historyLoading.value = true
    try {
      const rows = await listSessionChatMessages(sessionId, { silent: true })
      setMessages(mapServerMessages(rows))
    } catch (error) {
      console.warn('[ChatStream] hydrate skipped:', error?.message || error)
      clearMessages()
    } finally {
      historyLoading.value = false
    }
  }

  async function persistMessageToServer(role, content) {
    const sessionId = learningSession.currentSessionId
    if (!sessionId || !authStore.isRegistered) return
    try {
      await appendSessionChatMessage(sessionId, { role, content }, { silent: true })
      void learningSession.refreshSessionSummaries()
    } catch (error) {
      console.warn('[ChatStream] message persist skipped:', error?.message || error)
    }
  }

  function buildHistoryForApi() {
    return messages.value
      .map((m) => ({
        role: m.role === 'assistant' || m.role === 'system' ? m.role : 'user',
        content: String(m.content ?? '').trim(),
      }))
      .filter((m) => m.content.length > 0)
  }

  function buildRecentDialogueText() {
    return messages.value
      .slice(-4)
      .map((item) => `${item.role === 'user' ? '学生' : '助教'}：${item.content}`)
      .join('\n')
  }

  async function sendMessage(onStreamChunk, onComplete) {
    if (!inputMessage.value.trim() || isStreaming.value) return

    const allowed = await requireAuth('sendMessage', inputMessage.value.trim())
    if (!allowed) return

    const userMsg = inputMessage.value.trim()
    addMessage('user', userMsg)
    const sessionPromise = learningSession.ensureCurrentSession(userMsg).catch((error) => {
      console.warn('[ChatStream] session bootstrap continues in degraded mode:', error?.message || error)
      return null
    })
    void sessionPromise.then(() => persistMessageToServer('user', userMsg))
    inputMessage.value = ''

    isStreaming.value = true
    currentStreamContent.value = ''
    ragCitations.value = []
    ragGrounded.value = false
    ragStatus.value = ''
    knowledgeBaseHaMode.value = false
    groundingWarning.value = ''
    agentTrace.value = null
    hasReceivedFirstToken.value = false
    streamStatusText.value = '连接辅导模型...'
    abortController = new AbortController()
    startProgressTimer()

    try {
      await streamChatSse({
        systemPrompt: null,
        messages: buildHistoryForApi(),
        query: userMsg,
        enableRag: true,
        enableVoice: tts.voiceEnabled.value,
        learningSessionId: learningSession.currentSessionId
          ? Number(learningSession.currentSessionId)
          : null,
        studentProfileSnapshot: serializeProfileSnapshot(
          userProfile.profileSnapshot,
          userProfile.profileDimensions,
          userProfile.aiTeacherPreferences,
        ),
        emotionProfileSnapshot: `${userProfile.emotionState || ''} / ${userProfile.attentionState || ''}`.trim(),
        clientContext: JSON.stringify({
          timestamp: new Date().toISOString(),
          timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
          module: 'chat',
        }),
        tutoringMode: tutoringMode.value,
        signal: abortController.signal,
        onEvent: (eventName, data) => {
          if (eventName === 'status') {
            if (!hasReceivedFirstToken.value && data) {
              try {
                const parsed = typeof data === 'string' ? JSON.parse(data) : data
                streamStatusText.value = parsed?.message || streamStatusText.value
              } catch {
                streamStatusText.value = String(data)
              }
            }
            return
          }

          if (eventName === 'agent_trace') {
            try {
              agentTrace.value = typeof data === 'string' ? JSON.parse(data) : data
            } catch {
              agentTrace.value = null
            }
            return
          }

          if (eventName === 'memory_status') {
            if (!hasReceivedFirstToken.value) {
              streamStatusText.value = '检索知识库中...'
            }
            return
          }

          if (eventName === 'rag_context') {
            try {
              const parsed = typeof data === 'string' ? JSON.parse(data) : data
              ragCitations.value = Array.isArray(parsed?.citations) ? parsed.citations : []
              ragGrounded.value = Boolean(parsed?.grounded)
              ragStatus.value = parsed?.ragStatus || (ragGrounded.value ? 'GROUNDED' : 'DEGRADED')
              const haMode = ragStatus.value === 'HA_FALLBACK' || Boolean(parsed?.highAvailability)
              knowledgeBaseHaMode.value = haMode
              if (haMode) {
                knowledgeBaseDisconnected.value = false
              } else if (ragStatus.value === 'UNAVAILABLE') {
                knowledgeBaseDisconnected.value = true
              }
              if (!hasReceivedFirstToken.value) {
                if (haMode) {
                  streamStatusText.value = '知识库已切换至高可用模式'
                } else if (ragStatus.value === 'UNAVAILABLE') {
                  streamStatusText.value = '课程知识正在同步，辅导模型继续完整作答'
                } else {
                  streamStatusText.value = ragGrounded.value
                    ? `已检索到 ${ragCitations.value.length} 条课程证据`
                    : '正在结合模型知识完善教学回答'
                }
              }
            } catch {
              knowledgeBaseDisconnected.value = true
              knowledgeBaseHaMode.value = false
              if (!hasReceivedFirstToken.value) {
                streamStatusText.value = '课程知识正在同步，辅导模型继续完整作答'
              }
            }
            return
          }

          if (eventName === 'grounding_audit') {
            return
          }

          if (eventName === 'error') {
            let message = data || '流式生成失败'
            let errorCode = null
            try {
              const parsed = JSON.parse(data)
              message = parsed.message || message
              errorCode = parsed.code
              if (parsed.quota) {
                authStore.applyChatQuota(parsed.quota)
              }
            } catch {
              // plain text error payload
            }
            if (errorCode === 'GUEST_QUOTA_EXCEEDED') {
              authStore.openAuthModal('register', {
                type: 'sendMessage',
                payload: userMsg,
                timestamp: Date.now(),
              })
            }
            throw new Error(message)
          }

          if (eventName === 'complete') return

          if (eventName === 'content') {
            let chunk = data
            try {
              const parsed = JSON.parse(data)
              chunk = parsed.chunk ?? parsed.content ?? data
            } catch {
              // plain text data
            }
            if (chunk) {
              onFirstToken()
              currentStreamContent.value = sanitizeAssistantContent(currentStreamContent.value + chunk)
              onStreamChunk?.(chunk)
            }
          }
        },
      })

      const finalContent = sanitizeAssistantContent(currentStreamContent.value)
      if (finalContent) {
        addMessage('assistant', finalContent, {
          usedRag: ragGrounded.value,
          citations: ragCitations.value.slice(0, 8),
        })
        learningSession.setStreamingText(finalContent)
        void sessionPromise.then(() => persistMessageToServer('assistant', finalContent))
        if (tts.voiceEnabled.value) {
          void tts.speak(finalContent)
        }
        // 画像更新属于回答后的后台工作，不能与主聊天争抢模型连接或延迟完成态。
        void userProfile.extractFromLearningContext({
          conversationText: buildRecentDialogueText(),
          emotionSnapshot: `${userProfile.emotionState || ''} / ${userProfile.attentionState || ''}`.trim(),
          extractPhase: 'ASSISTANT_TURN',
        })
        metrics.record('CHAT_TURN', {
          textValue: userMsg.slice(0, 120),
          source: 'chat_stream',
        })
      }
      if (authStore.isGuest) {
        await authStore.syncGuestChatQuota()
      }
      onComplete?.(finalContent)
    } catch (err) {
      if (err.name !== 'AbortError') {
        console.error('Stream error:', err)
        const msg = String(err?.message || '')
        const hint = /502|503|Failed to fetch|NetworkError|ECONNREFUSED/i.test(msg)
          ? '辅导服务暂时未响应。你的问题已保留，请稍后重试。'
          : (msg && !msg.startsWith('Stream failed') ? msg : '抱歉，生成失败，请稍后重试。')
        addMessage('assistant', hint)
      }
    } finally {
      stopProgressTimer()
      isStreaming.value = false
      currentStreamContent.value = ''
      streamStatusText.value = ''
      abortController = null
    }
  }

  function stopStream() {
    if (abortController) {
      abortController.abort()
    }
    isStreaming.value = false
    streamStatusText.value = ''
    currentStreamContent.value = ''
    stopProgressTimer()
  }

  return proxyRefs({
    messages,
    inputMessage,
    isStreaming,
    currentStreamContent,
    streamStatusText,
    hasReceivedFirstToken,
    ragCitations,
    ragGrounded,
    ragStatus,
    knowledgeBaseDisconnected,
    knowledgeBaseHaMode,
    groundingWarning,
    agentTrace,
    historyLoading,
    tutoringMode,
    streamElapsedSeconds,
    voiceEnabled: tts.voiceEnabled,
    ttsSupported: tts.supported,
    ttsSpeaking: tts.isSpeaking,
    toggleVoice: tts.toggleVoice,
    stopSpeech: tts.stop,
    speakText: tts.speak,
    sendMessage,
    stopStream,
    clearMessages,
    setMessages,
    hydrateFromSession,
    loadGuestMessages,
  })
}
