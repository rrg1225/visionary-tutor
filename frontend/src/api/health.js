import axios from 'axios'

export async function fetchHealth() {
  const { data } = await axios.get('/api/health', { timeout: 8000 })
  return data
}

export async function fetchCompetitionReadiness() {
  const { data } = await axios.get('/api/health/competition-readiness', { timeout: 8000 })
  return data
}
