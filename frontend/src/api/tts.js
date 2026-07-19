import api from './index'

export async function fetchTtsHealth() {
  const response = await api.get('/tts/health', { silent: true, timeout: 5_000 })
  return response.data
}

export async function synthesizeSpeech({ text, voice, speed, format }) {
  const response = await api.post('/tts/synthesize', { text, voice, speed, format }, { silent: true })
  return response.data
}

export function resolveTtsAudioUrl(audioPath) {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  if (!audioPath) return ''
  if (audioPath.startsWith('http')) return audioPath
  if (audioPath.startsWith('/api/')) {
    return audioPath
  }
  return `${base}${audioPath.startsWith('/') ? audioPath.replace(/^\/api/, '') : `/${audioPath}`}`
}
