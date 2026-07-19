import { onBeforeUnmount, ref } from 'vue'
import { fetchTtsHealth, resolveTtsAudioUrl, synthesizeSpeech } from '../api/tts'
import { toastError, toastWarning } from '../utils/toast'

const DEFAULT_LANG = 'zh-CN'
const CHUNK_SIZE = 800

function stripMarkdown(text) {
  if (!text) return ''
  return text
    .replace(/```[\s\S]*?```/g, ' ')
    .replace(/`[^`]+`/g, ' ')
    .replace(/[#>*_\-\[\]()]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function splitForTts(text) {
  if (text.length <= CHUNK_SIZE) return [text]
  const chunks = []
  let rest = text
  while (rest.length > CHUNK_SIZE) {
    let cut = rest.lastIndexOf('。', CHUNK_SIZE)
    if (cut < CHUNK_SIZE * 0.35) {
      cut = rest.lastIndexOf('，', CHUNK_SIZE)
    }
    if (cut < CHUNK_SIZE * 0.35) {
      cut = CHUNK_SIZE
    }
    chunks.push(rest.slice(0, cut).trim())
    rest = rest.slice(cut).trim()
  }
  if (rest) chunks.push(rest)
  return chunks.filter(Boolean)
}

let cloudTtsEnabled = null
let cloudTtsCheckedAt = 0
let cloudTtsHealthPromise = null
let currentAudio = null

async function ensureCloudTtsStatus() {
  const cacheTtl = cloudTtsEnabled ? 300000 : 60000
  if (cloudTtsEnabled !== null && Date.now() - cloudTtsCheckedAt < cacheTtl) {
    return cloudTtsEnabled
  }
  if (cloudTtsHealthPromise) return cloudTtsHealthPromise
  cloudTtsHealthPromise = fetchTtsHealth()
    .then((health) => Boolean(health?.enabled))
    .catch(() => false)
    .then((enabled) => {
      cloudTtsEnabled = enabled
      cloudTtsCheckedAt = Date.now()
      return enabled
    })
    .finally(() => {
      cloudTtsHealthPromise = null
    })
  return cloudTtsHealthPromise
}

export function useTextToSpeech() {
  const isSpeaking = ref(false)
  const voiceEnabled = ref(false)
  const browserSupported = typeof window !== 'undefined' && 'speechSynthesis' in window
  const supported = ref(browserSupported)

  function pickVoice() {
    if (!browserSupported) return null
    const voices = window.speechSynthesis.getVoices()
    return voices.find((v) => v.lang.startsWith('zh'))
      || voices.find((v) => v.lang.startsWith('en'))
      || voices[0]
      || null
  }

  function stop() {
    if (currentAudio) {
      currentAudio.pause()
      if (currentAudio.src?.startsWith('blob:')) {
        URL.revokeObjectURL(currentAudio.src)
      }
      currentAudio = null
    }
    if (browserSupported) {
      window.speechSynthesis.cancel()
    }
    isSpeaking.value = false
  }

  async function loadCloudAudio(url) {
    const token = localStorage.getItem('vt_token')
    const audio = new Audio()
    if (token) {
      const response = await fetch(url, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (!response.ok) {
        throw new Error('音频加载失败')
      }
      const blob = await response.blob()
      audio.src = URL.createObjectURL(blob)
    } else {
      audio.src = url
    }
    return audio
  }

  async function speakWithCloud(text) {
    const result = await synthesizeSpeech({ text })
    const url = resolveTtsAudioUrl(result.audioUrl)
    const audio = await loadCloudAudio(url)
    currentAudio = audio

    return new Promise((resolve, reject) => {
      audio.onplay = () => { isSpeaking.value = true }
      audio.onended = () => {
        isSpeaking.value = false
        if (audio.src.startsWith('blob:')) {
          URL.revokeObjectURL(audio.src)
        }
        if (currentAudio === audio) {
          currentAudio = null
        }
        resolve(true)
      }
      audio.onerror = () => {
        isSpeaking.value = false
        currentAudio = null
        reject(new Error('音频播放失败'))
      }
      audio.play().catch(reject)
    })
  }

  function speakWithBrowser(text, { lang = DEFAULT_LANG, rate = 1.0 } = {}) {
    if (!browserSupported) return Promise.resolve(false)
    stop()
    return new Promise((resolve) => {
      const utterance = new SpeechSynthesisUtterance(text)
      utterance.lang = lang
      utterance.rate = rate
      const voice = pickVoice()
      if (voice) utterance.voice = voice
      utterance.onstart = () => { isSpeaking.value = true }
      utterance.onend = () => {
        isSpeaking.value = false
        resolve(true)
      }
      utterance.onerror = () => {
        isSpeaking.value = false
        resolve(false)
      }
      window.speechSynthesis.speak(utterance)
    })
  }

  async function speak(rawText, options = {}) {
    if (!rawText?.trim()) return false
    stop()
    const text = stripMarkdown(rawText).slice(0, 4000)
    if (!text) return false

    const cloudEnabled = await ensureCloudTtsStatus()
    if (cloudEnabled) {
      try {
        const chunks = splitForTts(text)
        for (const chunk of chunks) {
          await speakWithCloud(chunk)
        }
        return true
      } catch (error) {
        cloudTtsEnabled = false
        cloudTtsCheckedAt = Date.now()
        const message = error?.visionary?.message || error?.message || '云端朗读失败'
        if (browserSupported) {
          toastWarning(`${message}，已切换浏览器朗读`, 4000)
          const chunks = splitForTts(text)
          for (const chunk of chunks) {
            await speakWithBrowser(chunk, options)
          }
          return true
        }
        toastError(message)
        return false
      }
    }

    const chunks = splitForTts(text)
    for (const chunk of chunks) {
      await speakWithBrowser(chunk, options)
    }
    return true
  }

  function toggleVoice() {
    voiceEnabled.value = !voiceEnabled.value
    if (!voiceEnabled.value) stop()
    if (voiceEnabled.value) {
      void ensureCloudTtsStatus().then((enabled) => {
        supported.value = enabled || browserSupported
      })
    }
    return voiceEnabled.value
  }

  if (browserSupported && window.speechSynthesis.onvoiceschanged !== undefined) {
    window.speechSynthesis.onvoiceschanged = () => pickVoice()
  }

  onBeforeUnmount(() => stop())

  return {
    supported,
    isSpeaking,
    voiceEnabled,
    speak,
    stop,
    toggleVoice,
    loadCloudAudio,
    resolveTtsAudioUrl,
  }
}
