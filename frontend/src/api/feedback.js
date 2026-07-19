import api from './index.js'

export async function submitUserFeedback(payload) {
  const response = await api.post('/feedback', payload)
  return response.data
}

export async function fetchMyFeedback() {
  const response = await api.get('/feedback/mine', { silent: true })
  return response.data
}
