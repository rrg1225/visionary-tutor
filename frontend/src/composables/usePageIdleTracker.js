import { onMounted, onUnmounted } from 'vue'

const ACTIVITY_EVENTS = ['scroll', 'click', 'keydown', 'touchstart', 'pointerdown']

/**
 * Tracks last user interaction (scroll / click / touch / key) for multimodal idle validation.
 */
export function usePageIdleTracker() {
  let lastActionTime = Date.now()
  let listening = false

  function markActivity() {
    lastActionTime = Date.now()
  }

  function getIdleMs() {
    return Date.now() - lastActionTime
  }

  function isPageIdle(idleThresholdMs) {
    return getIdleMs() >= idleThresholdMs
  }

  function startListening() {
    if (listening) return
    listening = true
    ACTIVITY_EVENTS.forEach((eventName) => {
      window.addEventListener(eventName, markActivity, { passive: true, capture: true })
    })
  }

  function stopListening() {
    if (!listening) return
    listening = false
    ACTIVITY_EVENTS.forEach((eventName) => {
      window.removeEventListener(eventName, markActivity, { capture: true })
    })
  }

  onMounted(() => {
    markActivity()
    startListening()
  })

  onUnmounted(() => {
    stopListening()
  })

  return {
    markActivity,
    getIdleMs,
    isPageIdle,
    startListening,
    stopListening,
  }
}
