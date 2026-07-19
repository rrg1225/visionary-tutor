import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useConfusionOfferStore = defineStore('confusionOffer', () => {
  const active = ref(false)
  const isAccepting = ref(false)
  const message = ref('需要换一种讲法吗？')
  const hasExecutor = ref(false)

  /** @type {null | (() => Promise<void>)} */
  let acceptExecutor = null

  function registerAcceptExecutor(fn) {
    acceptExecutor = fn
    hasExecutor.value = typeof fn === 'function'
    return () => {
      if (acceptExecutor === fn) {
        acceptExecutor = null
        hasExecutor.value = false
      }
    }
  }

  function signalOffer(nextMessage) {
    if (nextMessage) message.value = nextMessage
    active.value = true
  }

  function dismiss() {
    active.value = false
  }

  async function accept() {
    if (isAccepting.value || !acceptExecutor) return
    isAccepting.value = true
    try {
      await acceptExecutor()
      active.value = false
    } finally {
      isAccepting.value = false
    }
  }

  return {
    active,
    isAccepting,
    hasExecutor,
    message,
    registerAcceptExecutor,
    signalOffer,
    dismiss,
    accept,
  }
})
