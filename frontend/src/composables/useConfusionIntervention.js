import { ref } from 'vue'
import { requestConfusionIntervention, requestEmotionIntervention } from '../api/intervention'
import { useConfusionOfferStore } from '../stores/confusionOffer'
import {
  INTERVENTION_DEBOUNCE_MS,
  EMOTION_API_DEBOUNCE_MS,
  PAGE_IDLE_MS,
  WINDOW_SIZE,
  API_CONFUSION_THRESHOLD,
  analyzeConfusionWindow,
  calculateConfusion,
  isHighConfidenceConfusion,
  isVisualConfusion,
} from '../utils/confusionScore'

const learningSessionId =
  typeof crypto !== 'undefined' && crypto.randomUUID
    ? crypto.randomUUID()
    : `session-${Date.now()}`

/**
 * Multi-modal weighted fusion confusion intervention engine.
 * Academic rationale: Visual (MediaPipe blendshapes) is deliberately low-weight (20%) as a supplementary cue.
 * Core decision relies on objective behavioral dwell/idle (50%) + text interaction signals (30%).
 * Trigger only after sustained Total_Confusion > 0.75 for continuous 3 seconds.
 */
export function useConfusionIntervention({ getIdleMs, getStudentProfileSnapshot, onIntervention }) {
  const confusionWindow = []
  const isDetecting = ref(false)
  const isIntervening = ref(false)
  let lastOfferAt = 0
  let lastEmotionApiAt = 0
  let emotionApiInFlight = false

  // Multi-modal scores (internal, non-reactive to avoid extra watchers)
  let vScore = 0 // Visual (20%)
  let bScore = 0 // Behavior (50%)
  let tScore = 0 // Text (30%)
  let lastHighStartTs = 0

  const confusionOffer = useConfusionOfferStore()

  function pushConfusionScore(score) {
    isDetecting.value = true
    confusionWindow.push(score)
    if (confusionWindow.length > WINDOW_SIZE) {
      confusionWindow.shift()
    }
    // V_score updated from visual pipeline (existing calculateConfusion feeds this)
    vScore = Math.max(vScore, score)
  }

  function resetWindow() {
    confusionWindow.length = 0
    isDetecting.value = false
    vScore = 0
    bScore = 0
    tScore = 0
    lastHighStartTs = 0
  }

  /**
   * Behavior score (B_score, weight 50%): derived from idle/dwell time via usePageIdleTracker.
   * Long abnormal停留 or no-scroll periods indicate cognitive hesitation.
   */
  function updateBehaviorScore(idleMs) {
    // Normalize: 0 idle → low B, long idle (e.g. >60s) → high B (anxiety signal)
    const normalized = Math.min(idleMs / 120_000, 1) // cap at 2 minutes
    bScore = normalized
  }

  /**
   * Text score (T_score, weight 30%): called by chat input before send.
   * pauseMs = typing hesitation duration; ambiguity = keyword vagueness (0-1).
   */
  function recordTextInputMetrics(pauseMs = 0, ambiguity = 0) {
    const pauseNorm = Math.min(pauseMs / 8000, 1)
    tScore = Math.max(tScore, (pauseNorm + ambiguity) / 2)
  }

  /**
   * Compute fused Total_Confusion with strict academic weighting.
   */
  function computeTotalConfusion() {
    const total = (vScore * 0.2) + (bScore * 0.5) + (tScore * 0.3)
    return Math.max(0, Math.min(1, total))
  }

  /**
   * Continuous 3-second gate: only signal when Total > 0.75 sustained for 3000ms.
   */
  function checkContinuousHighConfusion() {
    const total = computeTotalConfusion()
    const now = Date.now()
    if (total > 0.75) {
      if (lastHighStartTs === 0) {
        lastHighStartTs = now
      } else if (now - lastHighStartTs >= 3000) {
        return true
      }
    } else {
      lastHighStartTs = 0
    }
    return false
  }

  /**
   * 极高置信度 + 30s 防抖：自动调用 /api/ai/invoke，不弹 Toast、不跳转。
   */
  async function tryAutoEmotionIntervention() {
    if (emotionApiInFlight) return
    if (!isHighConfidenceConfusion(confusionWindow)) return

    const idleMs = getIdleMs?.() ?? 0
    if (idleMs < PAGE_IDLE_MS) return

    const now = Date.now()
    if (now - lastEmotionApiAt < EMOTION_API_DEBOUNCE_MS) return

    const { average, highFrames } = analyzeConfusionWindow(
      confusionWindow,
      API_CONFUSION_THRESHOLD,
    )

    emotionApiInFlight = true
    lastEmotionApiAt = now

    try {
      await requestEmotionIntervention({
        sessionId: learningSessionId,
        currentEmotion: 'CONFUSION',
        score: Number(average.toFixed(3)),
        studentProfileSnapshot: getStudentProfileSnapshot?.() || '',
        metadata: {
          state: 'CONFUSED',
          confusionScore: average,
          confusionAverage: average,
          highConfusionFrames: highFrames,
          windowSize: WINDOW_SIZE,
          idleMs,
        },
      })
    } catch (error) {
      console.warn('[ConfusionIntervention] Auto emotion API skipped:', error?.message || error)
      lastEmotionApiAt = now - EMOTION_API_DEBOUNCE_MS + 5000
    } finally {
      emotionApiInFlight = false
    }
  }

  /**
   * 中等置信度（多模态融合版）：只有 Total_Confusion 连续 3 秒 > 0.75 才触发 offer。
   * 行为特征 (B_score) 占主导权重，视觉仅作为低权重辅助。
   */
  function trySignalConfusionOffer() {
    const idleMs = getIdleMs?.() ?? 0
    updateBehaviorScore(idleMs)

    if (idleMs < PAGE_IDLE_MS) return
    if (!checkContinuousHighConfusion()) return

    const now = Date.now()
    if (now - lastOfferAt < INTERVENTION_DEBOUNCE_MS) return

    lastOfferAt = now
    // Dispatch to Pinia store (the canonical triggerConfusionOffer)
    confusionOffer.signalOffer()
  }

  /**
   * 用户主动点击「换一种讲法」后执行。
   * 此时已通过多模态持续高困惑验证。
   */
  async function acceptIntervention() {
    if (isIntervening.value) return

    const idleMs = getIdleMs?.() ?? 0
    updateBehaviorScore(idleMs)
    const { average, highFrames } = analyzeConfusionWindow(confusionWindow)
    const total = computeTotalConfusion()

    isIntervening.value = true
    lastEmotionApiAt = Date.now()

    const payload = {
      sessionId: learningSessionId,
      summary: 'User accepted multimodal confusion offer after sustained (V+B+T) fusion > 0.75 for 3s.',
      confusionAverage: Number(average.toFixed(3)),
      totalConfusion: Number(total.toFixed(3)),
      vScore: Number(vScore.toFixed(3)),
      bScore: Number(bScore.toFixed(3)),
      tScore: Number(tScore.toFixed(3)),
      highConfusionFrames: highFrames,
      idleMs,
      windowSize: WINDOW_SIZE,
    }

    try {
      const response = await requestConfusionIntervention({
        ...payload,
        studentProfileSnapshot: getStudentProfileSnapshot?.() || '',
      })
      onIntervention?.({ payload, response, userInitiated: true })
    } catch (error) {
      onIntervention?.({
        payload,
        error: error?.message || 'Intervention request failed',
        offline: true,
        userInitiated: true,
      })
    } finally {
      window.setTimeout(() => {
        isIntervening.value = false
        resetWindow()
        confusionOffer.dismiss()
      }, INTERVENTION_DEBOUNCE_MS)
    }
  }

  const unregisterAcceptExecutor = confusionOffer.registerAcceptExecutor(acceptIntervention)

  function dismissOffer() {
    confusionOffer.dismiss()
    resetWindow()
  }

  function processBlendshapes(blendshapes) {
    if (!blendshapes?.length) return
    const score = calculateConfusion(blendshapes)
    pushConfusionScore(score)
    // V_score is implicitly updated via push; visual remains 20% auxiliary weight.

    if (confusionWindow.length === WINDOW_SIZE) {
      trySignalConfusionOffer()
      tryAutoEmotionIntervention()
    }
  }

  return {
    isIntervening,
    isDetecting,
    processBlendshapes,
    resetWindow,
    acceptIntervention,
    dismissOffer,
    // New multi-modal entry points (non-breaking additions)
    recordTextInputMetrics,
    updateBehaviorScore,
    computeTotalConfusion,
    dispose: unregisterAcceptExecutor,
  }
}
