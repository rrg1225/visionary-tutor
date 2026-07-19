const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'

function authHeaders() {
  const headers = { 'Content-Type': 'application/json' }
  const token = localStorage.getItem('vt_token')
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const guestId = localStorage.getItem('vt_guest_id')
  if (guestId) {
    headers['X-Guest-Id'] = guestId
  }
  return headers
}

/**
 * 解析 SSE 文本块（支持 event: / data: 多行）。
 */
export function parseSseBuffer(buffer, onEvent) {
  const parts = buffer.split('\n\n')
  const remainder = parts.pop() ?? ''

  for (const part of parts) {
    if (!part.trim()) continue
    let eventName = 'message'
    const dataLines = []
    for (const line of part.split('\n')) {
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trim())
      }
    }
    const data = dataLines.join('\n')
    if (data) {
      onEvent(eventName, data)
    }
  }
  return remainder
}

/**
 * 从 messages 中取最后一条 user 文本，作为 RAG / query 回退。
 */
function resolveLastUserContent(messages) {
  if (!Array.isArray(messages)) return ''
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const m = messages[i]
    if (m?.role === 'user' && m.content?.trim()) {
      return m.content.trim()
    }
  }
  return ''
}

/**
 * POST /api/stream/chat — 流式对话（打字机）。
 * 强制携带 RAG 开关与检索 query，与后端 StreamChatRequest / RagRetrievalService 对齐。
 *
 * @param {object} options
 * @param {function(string, string): void} options.onEvent - (eventName, data)
 * @param {AbortSignal} [options.signal]
 */
export async function streamChatSse({
  systemPrompt,
  messages,
  query,
  enableRag = true,
  enableVoice = false,
  learningSessionId = null,
  studentProfileSnapshot = '',
  emotionProfileSnapshot = '',
  clientContext = '',
  tutoringMode = 'AUTO',
  onEvent,
  signal,
}) {
  const ragQuery = (query || resolveLastUserContent(messages) || '').trim()

  const response = await fetch(`${API_BASE}/stream/chat`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({
      systemPrompt: systemPrompt || null,
      messages: messages || [],
      query: ragQuery || null,
      enableRag: true,
      enableVoice: Boolean(enableVoice),
      ragQuery: ragQuery || null,
      taskType: 'RESOURCE_GENERATION',
      learningSessionId,
      studentProfileSnapshot,
      emotionProfileSnapshot,
      clientContext,
      tutoringMode,
      sensoryTags: 'rag,chat,knowledge,resource_generation',
    }),
    signal,
  })

  if (!response.ok) {
    const text = await response.text().catch(() => '')
    let message = text || `Stream failed (${response.status})`
    try {
      const parsed = JSON.parse(text)
      if (parsed.message) message = parsed.message
      if (parsed.errorCode === 'GUEST_QUOTA_EXCEEDED' || parsed.code === 'GUEST_QUOTA_EXCEEDED') {
        const err = new Error(message)
        err.code = 'GUEST_QUOTA_EXCEEDED'
        err.quota = parsed.details ?? parsed.quota
        throw err
      }
    } catch (parseErr) {
      if (parseErr?.code === 'GUEST_QUOTA_EXCEEDED') {
        throw parseErr
      }
    }
    throw new Error(message)
  }

  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('ReadableStream not supported')
  }

  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    buffer = parseSseBuffer(buffer, onEvent)
  }

  if (buffer.trim()) {
    parseSseBuffer(buffer + '\n\n', onEvent)
  }
}
