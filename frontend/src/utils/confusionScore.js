export const WINDOW_SIZE = 30
export const CONFUSION_THRESHOLD = 0.6
export const HIGH_CONFUSION_FRAME_MIN = 20
export const API_CONFUSION_THRESHOLD = 0.72
export const API_HIGH_CONFUSION_FRAME_MIN = 24

// Be conservative: visual assistance should feel like an optional nudge, not surveillance.
export const PAGE_IDLE_MS = 45_000
export const INTERVENTION_DEBOUNCE_MS = 180_000
export const EMOTION_API_DEBOUNCE_MS = 180_000

export function getBlendshapeScore(blendshapes, name) {
  const shape = blendshapes.find((item) => item.categoryName === name)
  return shape ? shape.score : 0
}

export function calculateConfusion(blendshapes) {
  const leftBrow = getBlendshapeScore(blendshapes, 'browDownLeft')
  const rightBrow = getBlendshapeScore(blendshapes, 'browDownRight')
  const leftSquint = getBlendshapeScore(blendshapes, 'eyeSquintLeft')
  return ((leftBrow + rightBrow) / 2) * 0.8 + leftSquint * 0.2
}

export function analyzeConfusionWindow(confusionWindow, threshold = CONFUSION_THRESHOLD) {
  if (confusionWindow.length < WINDOW_SIZE) {
    return { ready: false, highFrames: 0, average: 0 }
  }
  const slice = confusionWindow.slice(-WINDOW_SIZE)
  const highFrames = slice.filter((score) => score > threshold).length
  const average = slice.reduce((sum, value) => sum + value, 0) / slice.length
  return { ready: true, highFrames, average }
}

export function isVisualConfusion(confusionWindow) {
  const { ready, highFrames } = analyzeConfusionWindow(confusionWindow, CONFUSION_THRESHOLD)
  return ready && highFrames > HIGH_CONFUSION_FRAME_MIN
}

export function isHighConfidenceConfusion(confusionWindow) {
  const { ready, highFrames, average } = analyzeConfusionWindow(
    confusionWindow,
    API_CONFUSION_THRESHOLD,
  )
  return (
    ready
    && highFrames >= API_HIGH_CONFUSION_FRAME_MIN
    && average >= API_CONFUSION_THRESHOLD
  )
}
