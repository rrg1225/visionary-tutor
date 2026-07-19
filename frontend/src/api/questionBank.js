import api from './index'

export async function recordQuestionAttempts(attempts) {
  const response = await api.post('/questions/attempts', { attempts }, { silent: true })
  return response.data
}

export async function fetchWrongBook() {
  const response = await api.get('/questions/wrong-book', { silent: true })
  return response.data
}

export async function fetchDueReviews() {
  const response = await api.get('/questions/reviews/due', { silent: true })
  return response.data
}

export async function reviewQuestionAttempt(attemptId, correct) {
  const response = await api.put(`/questions/attempts/${attemptId}/review`, { correct })
  return response.data
}
