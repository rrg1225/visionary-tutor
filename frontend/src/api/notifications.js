import api from './index'

export async function fetchUnreadNotifications(options = {}) {
  const response = await api.get('/notifications/unread', { silent: options.silent ?? true })
  return response.data
}

export async function markNotificationRead(id) {
  await api.post(`/notifications/${id}/read`, null, { silent: true })
}

export function buildNotificationWsUrl(token) {
  const apiBase = import.meta.env.VITE_API_BASE_URL || '/api'
  const origin = window.location.origin
  const wsOrigin = origin.replace(/^http/, 'ws')
  const wsPath = apiBase.startsWith('http')
    ? apiBase.replace(/^http/, 'ws').replace(/\/api$/, '') + '/ws/notifications'
    : `${wsOrigin}/ws/notifications`
  return `${wsPath}?token=${encodeURIComponent(token)}`
}
