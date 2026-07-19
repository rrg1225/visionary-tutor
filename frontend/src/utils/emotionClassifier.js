/**
 * FACS-Based Emotion Classification from MediaPipe Face Blendshapes
 * 
 * Implements high-precision weighted rules for detecting student cognitive states:
 * - Confusion: Brow lowering + eye squinting
 * - Fatigue: Prolonged eye closure (blinking) + jaw dropping (yawning)
 * 
 * All scores are strictly clamped between 0.0 and 1.0.
 */

/**
 * Extract blendshape score by category name.
 * @param {Array<{categoryName: string, score: number}>} blendshapes
 * @param {string} name
 * @returns {number} Score between 0.0 and 1.0, or 0 if not found
 */
export function getBlendshapeScore(blendshapes, name) {
  if (!Array.isArray(blendshapes)) return 0
  const shape = blendshapes.find((item) => item.categoryName === name)
  return shape ? Math.max(0, Math.min(1, shape.score)) : 0
}

/**
 * Calculate Confusion Score based on FACS Action Units.
 * 
 * Indicators:
 * - AU4 (Brow Lowerer): browDownLeft + browDownRight
 * - AU7 (Lid Tightener): eyeSquintLeft + eyeSquintRight
 * 
 * Formula: (browDownLeft + browDownRight) * 0.4 + (eyeSquintLeft + eyeSquintRight) * 0.1
 * Normalized to 0.0 - 1.0 range.
 * 
 * @param {Array<{categoryName: string, score: number}>} blendshapes
 * @returns {number} Confusion score between 0.0 and 1.0
 */
export function calculateConfusionScore(blendshapes) {
  const browDownLeft = getBlendshapeScore(blendshapes, 'browDownLeft')
  const browDownRight = getBlendshapeScore(blendshapes, 'browDownRight')
  const eyeSquintLeft = getBlendshapeScore(blendshapes, 'eyeSquintLeft')
  const eyeSquintRight = getBlendshapeScore(blendshapes, 'eyeSquintRight')

  /**
   * Boundary condition analysis:
   * - Max browDown per side: ~1.0 (AU4 full activation)
   * - Max eyeSquint per side: ~1.0 (AU7 full activation)
   * - Weighted max: (1.0 + 1.0) * 0.4 + (1.0 + 1.0) * 0.1 = 0.8 + 0.2 = 1.0
   * - This gives us natural normalization to 1.0 at maximum expression
   */
  const rawScore = (browDownLeft + browDownRight) * 0.4 + (eyeSquintLeft + eyeSquintRight) * 0.1
  
  return Math.max(0, Math.min(1, rawScore))
}

/**
 * Fatigue Detection State Tracker.
 * Tracks prolonged eye closure for blink-based fatigue detection.
 */
const fatigueTracker = {
  /** Timestamp when both eyes first closed above threshold */
  blinkStartTime: null,
  
  /** Threshold for considering eyes as "closed" */
  EYE_BLINK_THRESHOLD: 0.7,
  
  /** Duration (ms) of eye closure to trigger fatigue alert */
  PROLONGED_BLINK_DURATION: 1500,
  
  /** Last calculated fatigue score for state continuity */
  lastFatigueScore: 0
}

/**
 * Calculate Fatigue Score based on FACS Action Units.
 * 
 * Indicators:
 * - AU45 (Blink): eyeBlinkLeft + eyeBlinkRight (prolonged closure indicates drowsiness)
 * - AU26 (Jaw Drop): jawOpen (indicates yawning)
 * 
 * Logic:
 * 1. If both eyeBlinkLeft and eyeBlinkRight > 0.7 for > 1.5 seconds → fatigue = 1.0
 * 2. Otherwise, incorporate jawOpen * 0.5 as a secondary indicator
 * 
 * @param {Array<{categoryName: string, score: number}>} blendshapes
 * @param {number} timestamp - Current frame timestamp in milliseconds
 * @returns {number} Fatigue score between 0.0 and 1.0
 */
export function calculateFatigueScore(blendshapes, timestamp = performance.now()) {
  const eyeBlinkLeft = getBlendshapeScore(blendshapes, 'eyeBlinkLeft')
  const eyeBlinkRight = getBlendshapeScore(blendshapes, 'eyeBlinkRight')
  const jawOpen = getBlendshapeScore(blendshapes, 'jawOpen')

  /**
   * Prolonged eye closure detection (drowsiness indicator)
   * Boundary condition: Both eyes must be closed above threshold simultaneously
   */
  const bothEyesClosed = eyeBlinkLeft > fatigueTracker.EYE_BLINK_THRESHOLD && 
                         eyeBlinkRight > fatigueTracker.EYE_BLINK_THRESHOLD

  if (bothEyesClosed) {
    if (fatigueTracker.blinkStartTime === null) {
      // Eyes just closed - record start time
      fatigueTracker.blinkStartTime = timestamp
    } else {
      // Eyes still closed - check duration
      const blinkDuration = timestamp - fatigueTracker.blinkStartTime
      if (blinkDuration >= fatigueTracker.PROLONGED_BLINK_DURATION) {
        // Prolonged closure detected - maximum fatigue alert
        fatigueTracker.lastFatigueScore = 1.0
        return 1.0
      }
    }
  } else {
    // Eyes opened - reset blink tracking
    fatigueTracker.blinkStartTime = null
  }

  /**
   * Yawning detection via jaw opening (secondary fatigue indicator)
   * Normalized contribution: jawOpen maxes at 1.0, weighted at 0.5
   */
  const jawContribution = jawOpen * 0.5
  
  /**
   * Combined scoring:
   * - If not in prolonged blink, use jaw contribution
   * - Gradual scaling based on current eye blink (partial closure)
   * - Max score without prolonged blink is 0.5 (from jaw only)
   */
  const eyeClosureSeverity = Math.min(
    eyeBlinkLeft, 
    eyeBlinkRight
  ) * 0.5 // Scale partial closure to 0-0.5 range
  
  const rawScore = Math.max(jawContribution, eyeClosureSeverity)
  const clampedScore = Math.max(0, Math.min(1, rawScore))
  
  fatigueTracker.lastFatigueScore = clampedScore
  return clampedScore
}

/**
 * Reset the internal fatigue tracker state.
 * Call this when switching sessions or users.
 */
export function resetFatigueTracker() {
  fatigueTracker.blinkStartTime = null
  fatigueTracker.lastFatigueScore = 0
}

/**
 * Complete emotion analysis result.
 * @typedef {Object} EmotionAnalysisResult
 * @property {number} confusionScore - 0.0 to 1.0
 * @property {boolean} isConfused - Whether confusion exceeds threshold
 * @property {number} fatigueScore - 0.0 to 1.0
 * @property {boolean} isFatigued - Whether fatigue exceeds threshold
 * @property {Object} rawScores - Raw blendshape values for debugging
 */

/**
 * Analyze blendshapes and return complete emotion classification.
 * 
 * @param {Array<{categoryName: string, score: number}>} blendshapes
 * @param {number} timestamp - Current frame timestamp in milliseconds
 * @param {Object} thresholds - Optional custom thresholds
 * @param {number} thresholds.confusion - Confusion trigger threshold (default: 0.6)
 * @param {number} thresholds.fatigue - Fatigue trigger threshold (default: 0.7)
 * @returns {EmotionAnalysisResult}
 */
export function analyzeEmotions(
  blendshapes, 
  timestamp = performance.now(),
  thresholds = { confusion: 0.6, fatigue: 0.7 }
) {
  const confusionScore = calculateConfusionScore(blendshapes)
  const fatigueScore = calculateFatigueScore(blendshapes, timestamp)

  return {
    confusionScore,
    isConfused: confusionScore > thresholds.confusion,
    fatigueScore,
    isFatigued: fatigueScore > thresholds.fatigue,
    rawScores: {
      browDownLeft: getBlendshapeScore(blendshapes, 'browDownLeft'),
      browDownRight: getBlendshapeScore(blendshapes, 'browDownRight'),
      eyeSquintLeft: getBlendshapeScore(blendshapes, 'eyeSquintLeft'),
      eyeSquintRight: getBlendshapeScore(blendshapes, 'eyeSquintRight'),
      eyeBlinkLeft: getBlendshapeScore(blendshapes, 'eyeBlinkLeft'),
      eyeBlinkRight: getBlendshapeScore(blendshapes, 'eyeBlinkRight'),
      jawOpen: getBlendshapeScore(blendshapes, 'jawOpen'),
    }
  }
}

/**
 * Session-based persistence tracker for intervention triggering.
 * Ensures emotions persist above threshold for 3+ seconds before alerting backend.
 */
export class EmotionPersistenceTracker {
  constructor(persistenceMs = 3000) {
    this.persistenceMs = persistenceMs
    this.confusionState = { active: false, startTime: null, lastTriggered: null }
    this.fatigueState = { active: false, startTime: null, lastTriggered: null }
    /** Minimum time between repeated interventions (debounce) */
    this.INTERVENTION_DEBOUNCE_MS = 30000 // 30 seconds between same-type interventions
  }

  /**
   * Update tracker with new emotion scores and check if intervention should trigger.
   * 
   * @param {EmotionAnalysisResult} emotionResult
   * @param {number} timestamp - Current timestamp in milliseconds
   * @returns {{ shouldTrigger: boolean, emotionType: string|null, score: number }}
   */
  update(emotionResult, timestamp = performance.now()) {
    const result = { shouldTrigger: false, emotionType: null, score: 0 }

    // Track Confusion persistence
    if (emotionResult.isConfused) {
      if (!this.confusionState.active) {
        // Confusion just started
        this.confusionState.active = true
        this.confusionState.startTime = timestamp
      } else {
        // Confusion ongoing - check if persisted long enough
        const duration = timestamp - this.confusionState.startTime
        const sinceLastTrigger = timestamp - (this.confusionState.lastTriggered || 0)
        
        if (duration >= this.persistenceMs && sinceLastTrigger >= this.INTERVENTION_DEBOUNCE_MS) {
          result.shouldTrigger = true
          result.emotionType = 'CONFUSION'
          result.score = emotionResult.confusionScore
          this.confusionState.lastTriggered = timestamp
        }
      }
    } else {
      // Confusion ended - reset
      this.confusionState.active = false
      this.confusionState.startTime = null
    }

    // Track Fatigue persistence (only if confusion didn't trigger)
    if (!result.shouldTrigger && emotionResult.isFatigued) {
      if (!this.fatigueState.active) {
        // Fatigue just started
        this.fatigueState.active = true
        this.fatigueState.startTime = timestamp
      } else {
        // Fatigue ongoing - check if persisted long enough
        const duration = timestamp - this.fatigueState.startTime
        const sinceLastTrigger = timestamp - (this.fatigueState.lastTriggered || 0)
        
        if (duration >= this.persistenceMs && sinceLastTrigger >= this.INTERVENTION_DEBOUNCE_MS) {
          result.shouldTrigger = true
          result.emotionType = 'FATIGUE'
          result.score = emotionResult.fatigueScore
          this.fatigueState.lastTriggered = timestamp
        }
      }
    } else if (!emotionResult.isFatigued) {
      // Fatigue ended - reset
      this.fatigueState.active = false
      this.fatigueState.startTime = null
    }

    return result
  }

  /**
   * Reset all tracking state.
   */
  reset() {
    this.confusionState = { active: false, startTime: null, lastTriggered: null }
    this.fatigueState = { active: false, startTime: null, lastTriggered: null }
  }
}
