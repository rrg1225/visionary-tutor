import api from './index'

export async function fetchMemories() {
  const response = await api.get('/memory')
  return response.data
}

export async function fetchPendingMemories() {
  const response = await api.get('/memory/pending')
  return response.data
}

export async function fetchMemoryLogs() {
  const response = await api.get('/memory/logs')
  return response.data
}

export async function upsertManualMemory(payload) {
  const response = await api.put('/memory/manual', payload)
  return response.data
}

export async function approveMemory(memoryId) {
  const response = await api.post(`/memory/${memoryId}/approve`)
  return response.data
}

export async function rejectMemory(memoryId) {
  const response = await api.post(`/memory/${memoryId}/reject`)
  return response.data
}

export async function fetchPathSteps(learningSessionId) {
  const response = await api.get('/learning-path/steps', {
    params: { learningSessionId },
  })
  return response.data
}

export async function updatePathStepStatus(learningSessionId, stepOrder, status) {
  const response = await api.put(`/learning-path/steps/${stepOrder}/status`, { status }, {
    params: { learningSessionId },
  })
  return response.data
}

export async function recordResourceUsage(payload) {
  const response = await api.post('/resources/usage/record', payload)
  return response.data
}
