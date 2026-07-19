import api from './index'

export async function listPublicTextbooks() {
  const response = await api.get('/library/textbooks')
  return response.data
}

export async function listMyTextbooks() {
  const response = await api.get('/library/textbooks/mine')
  return response.data
}

export async function getTextbook(id) {
  const response = await api.get(`/library/textbooks/${id}`)
  return response.data
}

export async function submitTextbook(payload) {
  const response = await api.post('/library/textbooks', payload)
  return response.data
}

export async function fetchPendingRecommendationPush() {
  const response = await api.get('/library/recommendations/pending')
  return response.data
}

export async function consumeRecommendationPush(pushId) {
  await api.post(`/library/recommendations/push/${pushId}/consume`)
}

export async function listPendingTextbooks() {
  const response = await api.get('/library/admin/textbooks/pending')
  return response.data
}

export async function getTextbookForReview(id) {
  const response = await api.get(`/library/admin/textbooks/${id}`)
  return response.data
}

export async function approveTextbook(id) {
  const response = await api.post(`/library/admin/textbooks/${id}/approve`)
  return response.data
}

export async function rejectTextbook(id, reason) {
  const response = await api.post(`/library/admin/textbooks/${id}/reject`, { reason })
  return response.data
}
