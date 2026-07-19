import { defineStore } from 'pinia'
import { ref } from 'vue'
import { buildNotificationWsUrl, fetchUnreadNotifications, markNotificationRead } from '../api/notifications'
import { consumeRecommendationPush } from '../api/library'

const E2E_HOOKS_ENABLED = import.meta.env.VITE_E2E_HOOKS === 'true'
// 生产反向代理未显式开启 Upgrade 时使用轮询，功能不变且不会产生握手报错。
const WEBSOCKET_ENABLED = import.meta.env.VITE_NOTIFICATION_WS_ENABLED === 'true' || import.meta.env.DEV
const MAX_RECONNECT_ATTEMPTS = 6
const MAX_RECONNECT_DELAY_MS = 30000
const POLLING_INTERVAL_MS = 30000

export const useNotificationStore = defineStore('notification', () => {
  const connected = ref(false)
  const pendingRecommendation = ref(null)
  const ugcReviewNotice = ref('')
  const socket = ref(null)
  const reconnectTimer = ref(null)
  const reconnectAttempts = ref(0)
  const pollingTimer = ref(null)
  let manualDisconnect = false
  // 握手被拒（HTTP 401/403）时浏览器 close code 是 1006，识别不出鉴权失败；
  // 「从未 open 就 close」连续出现即认定握手被拒，停止重连避免控制台刷屏。
  let handshakeFailures = 0
  const MAX_HANDSHAKE_FAILURES = 2

  function handleMessage(raw) {
    let message
    try {
      message = JSON.parse(raw)
    } catch {
      return
    }
    if (message.type === 'RECOMMENDATION_PUSH') {
      pendingRecommendation.value = message.payload || null
      return
    }
    if (message.type === 'UGC_REVIEW_RESULT') {
      const payload = message.payload || {}
      if (payload.status === 'approved') {
        ugcReviewNotice.value = `教材「${payload.title || ''}」已通过审核`
      } else {
        ugcReviewNotice.value = `教材「${payload.title || ''}」未通过审核：${payload.reason || '请修改后重试'}`
      }
      if (message.id) {
        markNotificationRead(message.id).catch(() => {})
      }
    }
  }

  async function syncUnread() {
    const unread = await fetchUnreadNotifications()
    for (const item of unread) {
      handleMessage(JSON.stringify({
        id: item.id,
        type: item.type,
        payload: tryParse(item.payloadJson),
      }))
    }
  }

  function connect() {
    if (E2E_HOOKS_ENABLED) {
      return
    }
    const token = localStorage.getItem('vt_token')
    if (!token || socket.value) {
      return
    }
    if (!WEBSOCKET_ENABLED) {
      manualDisconnect = false
      startPolling()
      return
    }
    manualDisconnect = false
    let ws
    try {
      ws = new WebSocket(buildNotificationWsUrl(token))
    } catch {
      startPolling()
      scheduleReconnect()
      return
    }
    socket.value = ws
    let opened = false

    ws.onopen = () => {
      opened = true
      connected.value = true
      reconnectAttempts.value = 0
      handshakeFailures = 0
      stopPolling()
      syncUnread().catch(() => {})
    }

    ws.onmessage = (event) => handleMessage(event.data)

    ws.onclose = (event) => {
      connected.value = false
      socket.value = null
      const authenticationRejected = event.code === 1008 || event.code === 4401 || event.code === 4403
      if (!opened) {
        handshakeFailures += 1
      }
      if (manualDisconnect || authenticationRejected) {
        return
      }
      startPolling()
      if (handshakeFailures >= MAX_HANDSHAKE_FAILURES) {
        // 大概率是 Token 失效/无权限，重连只会继续被拒；轮询已兜底，静默退出。
        return
      }
      scheduleReconnect()
    }

    ws.onerror = () => {
      ws.close()
    }

    const heartbeat = setInterval(() => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send('ping')
      } else {
        clearInterval(heartbeat)
      }
    }, 30000)
  }

  function scheduleReconnect() {
    if (E2E_HOOKS_ENABLED) return
    if (reconnectTimer.value) return
    if (!localStorage.getItem('vt_token')) return
    if (reconnectAttempts.value >= MAX_RECONNECT_ATTEMPTS) {
      console.warn('[Notification] WebSocket reconnect paused after repeated failures; polling remains available.')
      startPolling()
      return
    }
    const attempt = reconnectAttempts.value
    const baseDelay = Math.min(MAX_RECONNECT_DELAY_MS, 1500 * (2 ** attempt))
    const delay = baseDelay + Math.round(Math.random() * 500)
    reconnectAttempts.value += 1
    reconnectTimer.value = window.setTimeout(() => {
      reconnectTimer.value = null
      connect()
    }, delay)
  }

  function startPolling() {
    if (E2E_HOOKS_ENABLED || manualDisconnect || pollingTimer.value) return
    if (!localStorage.getItem('vt_token')) return
    void syncUnread().catch(() => {})
    pollingTimer.value = window.setInterval(() => {
      if (!connected.value && localStorage.getItem('vt_token')) {
        void syncUnread().catch(() => {})
      }
    }, POLLING_INTERVAL_MS)
  }

  function stopPolling() {
    if (pollingTimer.value) {
      window.clearInterval(pollingTimer.value)
      pollingTimer.value = null
    }
  }

  function disconnect() {
    manualDisconnect = true
    reconnectAttempts.value = 0
    handshakeFailures = 0
    stopPolling()
    if (reconnectTimer.value) {
      clearTimeout(reconnectTimer.value)
      reconnectTimer.value = null
    }
    if (socket.value) {
      socket.value.close()
      socket.value = null
    }
    connected.value = false
  }

  async function dismissRecommendation() {
    const pushId = pendingRecommendation.value?.pushId
    pendingRecommendation.value = null
    if (pushId) {
      await consumeRecommendationPush(pushId)
    }
  }

  function clearUgcNotice() {
    ugcReviewNotice.value = ''
  }

  return {
    connected,
    pendingRecommendation,
    ugcReviewNotice,
    connect,
    disconnect,
    syncUnread,
    dismissRecommendation,
    clearUgcNotice,
  }
})

function tryParse(json) {
  try {
    return JSON.parse(json)
  } catch {
    return {}
  }
}
