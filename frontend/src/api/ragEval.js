import api from './index'

export async function fetchLatestRagEval(options = {}) {
  const response = await api.get('/admin/rag-eval/latest', { silent: options.silent })
  return response.data
}
