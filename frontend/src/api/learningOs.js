import { parseSseBuffer } from './stream'

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'

function authHeaders() {
  const headers = {}
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

export async function fetchRemediationProgress(runId) {
  const response = await fetch(
    `${API_BASE}/learning-os/remediation/progress?runId=${encodeURIComponent(runId)}`,
    { headers: authHeaders() },
  )
  if (!response.ok) {
    throw new Error(`进度查询失败 (${response.status})`)
  }
  return response.json()
}

/**
 * SSE 订阅补救资源生成进度。
 * @returns {Promise<object|null>} 终态 progress 或 null（超时/中断）
 */
export async function streamRemediationProgress({ runId, onProgress, signal }) {
  const response = await fetch(
    `${API_BASE}/learning-os/remediation/progress/stream?runId=${encodeURIComponent(runId)}`,
    { headers: authHeaders(), signal },
  )
  if (!response.ok) {
    throw new Error(`进度流连接失败 (${response.status})`)
  }

  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('ReadableStream not supported')
  }

  const decoder = new TextDecoder()
  let buffer = ''
  let latest = null

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    buffer = parseSseBuffer(buffer, (eventName, data) => {
      if (eventName === 'progress' || eventName === 'complete') {
        try {
          latest = typeof data === 'string' ? JSON.parse(data) : data
          onProgress?.(latest)
        } catch {
          // ignore malformed payload
        }
      }
      if (eventName === 'complete') {
        return
      }
    })
    if (latest?.status === 'COMPLETE' || latest?.status === 'FAILED') {
      return latest
    }
  }

  if (buffer.trim()) {
    parseSseBuffer(`${buffer}\n\n`, (eventName, data) => {
      if (eventName === 'progress' || eventName === 'complete') {
        try {
          latest = typeof data === 'string' ? JSON.parse(data) : data
          onProgress?.(latest)
        } catch {
          // ignore
        }
      }
    })
  }

  return latest
}
