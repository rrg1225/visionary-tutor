import api from './index'

export async function fetchAgentTrace(params = {}) {
  const response = await api.get('/agent/audit/trace', {
    params,
    silent: params.silent,
  })
  return response.data
}
