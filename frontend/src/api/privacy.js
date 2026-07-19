import api from './index'

export async function fetchPrivacyExport(options = {}) {
  const response = await api.get('/privacy/export', { silent: options.silent })
  return response.data
}

export async function fetchCameraPolicy(options = {}) {
  const response = await api.get('/privacy/camera-policy', { silent: options.silent })
  return response.data
}

export async function deleteLearningMemory() {
  const response = await api.delete('/privacy/memory')
  return response.data
}

export async function deleteAccount() {
  const response = await api.delete('/privacy/account')
  return response.data
}
