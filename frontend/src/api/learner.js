import api from './index'

export async function fetchLearnerState(options = {}) {
  const response = await api.get('/learner/state', { silent: options.silent })
  return response.data
}
