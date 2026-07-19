import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useAuthStore } from './authStore'

export const GUIDE_VERSION = 3

function storageKey(userId, isGuest) {
  const identity = isGuest ? `guest:${userId || 'anonymous'}` : `user:${userId || 'anonymous'}`
  return `visionary-tutor-guide:${identity}`
}

function readState(key) {
  try {
    return JSON.parse(localStorage.getItem(key) || 'null')
  } catch {
    return null
  }
}

export const useGuideStore = defineStore('guide', () => {
  const authStore = useAuthStore()
  const active = ref(false)
  const currentStep = ref(0)
  const completedVersion = ref(0)
  const skippedVersion = ref(0)
  const hydratedKey = ref('')

  const shouldOfferGuide = computed(() => (
    completedVersion.value < GUIDE_VERSION && skippedVersion.value < GUIDE_VERSION
  ))

  function keyForCurrentUser() {
    return storageKey(authStore.currentUserId, authStore.isGuest)
  }

  function hydrate() {
    const key = keyForCurrentUser()
    if (hydratedKey.value === key) return
    hydratedKey.value = key
    const saved = readState(key)
    completedVersion.value = Number(saved?.completedVersion || 0)
    skippedVersion.value = Number(saved?.skippedVersion || 0)
    currentStep.value = 0
    active.value = false
  }

  function persist() {
    const key = keyForCurrentUser()
    hydratedKey.value = key
    localStorage.setItem(key, JSON.stringify({
      completedVersion: completedVersion.value,
      skippedVersion: skippedVersion.value,
      updatedAt: new Date().toISOString(),
    }))
  }

  function start({ force = false } = {}) {
    hydrate()
    if (!force && !shouldOfferGuide.value) return false
    currentStep.value = 0
    active.value = true
    return true
  }

  function setStep(index) {
    currentStep.value = Math.max(0, Number(index) || 0)
  }

  function complete() {
    completedVersion.value = GUIDE_VERSION
    skippedVersion.value = 0
    active.value = false
    currentStep.value = 0
    persist()
  }

  function skip() {
    skippedVersion.value = GUIDE_VERSION
    active.value = false
    currentStep.value = 0
    persist()
  }

  return {
    active,
    currentStep,
    completedVersion,
    skippedVersion,
    shouldOfferGuide,
    hydrate,
    start,
    setStep,
    complete,
    skip,
  }
})
