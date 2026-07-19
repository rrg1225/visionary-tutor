import api from './index'

let sandboxHealthPromise = null
let sandboxHealthCache = null
let sandboxHealthCheckedAt = 0

export async function fetchSandboxHealth() {
  if (sandboxHealthCache && Date.now() - sandboxHealthCheckedAt < 60000) {
    return sandboxHealthCache
  }
  if (sandboxHealthPromise) return sandboxHealthPromise
  sandboxHealthPromise = api.get('/sandbox/health', { silent: true })
    .then((response) => response.data)
    .catch(() => ({ available: false, message: '代码沙箱暂不可用' }))
    .then((health) => {
      sandboxHealthCache = health
      sandboxHealthCheckedAt = Date.now()
      return health
    })
    .finally(() => {
      sandboxHealthPromise = null
    })
  return sandboxHealthPromise
}

export async function executeSandboxCode(code) {
  const response = await api.post('/sandbox/execute', { code })
  return response.data
}

export async function validateSandboxCode(code) {
  const response = await api.post('/sandbox/validate', { code })
  return response.data
}
