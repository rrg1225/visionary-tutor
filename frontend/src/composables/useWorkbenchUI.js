import { reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useConfusionOfferStore } from '../stores/confusionOffer'

function isDesktopViewport() {
  return typeof window !== 'undefined' && window.matchMedia('(min-width: 1024px)').matches
}

function resolveInitialDrawerCollapsed() {
  if (isDesktopViewport()) {
    return false
  }
  return true
}

export function useWorkbenchUI() {
  const drawerCollapsed = ref(resolveInitialDrawerCollapsed())
  const activePanel = ref('profile')
  const resonanceState = ref('idle')
  // Privacy-safe default: every page load starts with the camera off.
  const cameraAssistEnabled = ref(false)

  const confusionOffer = useConfusionOfferStore()
  const { active: confusionOfferActive, message: confusionOfferMessage, isAccepting: confusionOfferLoading } =
    storeToRefs(confusionOffer)

  const panels = [
    { id: 'profile', title: '学情画像' },
    { id: 'diagnosis', title: '知识诊断' },
    { id: 'resources', title: '资源预览' },
    { id: 'assessment', title: '测评闭环' },
  ]

  function toggleDrawer() {
    drawerCollapsed.value = !drawerCollapsed.value
  }

  function setActivePanel(panel) {
    activePanel.value = panel
    drawerCollapsed.value = false
  }

  function expandDrawer(panel = activePanel.value) {
    activePanel.value = panel
    drawerCollapsed.value = false
  }

  function syncDrawerFromContext({
    hasResources = false,
    profileReady = false,
    isProfileComplete = profileReady,
    generating = false,
    isGeneratingResources = generating,
  } = {}) {
    if (hasResources || isProfileComplete || isGeneratingResources) {
      drawerCollapsed.value = false
    }
  }

  function setResonanceState(state) {
    resonanceState.value = state
  }

  function setCameraAssistEnabled(value) {
    cameraAssistEnabled.value = Boolean(value)
    localStorage.setItem('vt_camera_assist_enabled', String(cameraAssistEnabled.value))
    resonanceState.value = cameraAssistEnabled.value ? 'listening' : 'idle'
  }

  watch(
    confusionOfferActive,
    (active) => {
      if (active) {
        resonanceState.value = 'confusion-offer'
      } else if (resonanceState.value === 'confusion-offer') {
        resonanceState.value = cameraAssistEnabled.value ? 'listening' : 'idle'
      }
    },
    { immediate: true },
  )

  async function acceptConfusionOffer() {
    await confusionOffer.accept()
  }

  function dismissConfusionOffer() {
    confusionOffer.dismiss()
  }

  return reactive({
    drawerCollapsed,
    activePanel,
    resonanceState,
    cameraAssistEnabled,
    confusionOfferActive,
    confusionOfferMessage,
    confusionOfferLoading,
    panels,
    toggleDrawer,
    setActivePanel,
    expandDrawer,
    syncDrawerFromContext,
    setResonanceState,
    setCameraAssistEnabled,
    acceptConfusionOffer,
    dismissConfusionOffer,
  })
}
