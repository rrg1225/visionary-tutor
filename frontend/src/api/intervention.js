import { invokeAiAgent } from './ai'

/**
 * Build facialToken JSON aligned with EmotionProfileAgentHandler / AgentInvokeRequest.
 * Expression scores and intervention context live in facialToken (string field).
 */
function buildFacialToken(payload) {
  const score = Math.max(0, Math.min(1, Number(payload.score) || 0))
  const metadata = payload.metadata || {}

  return JSON.stringify({
    sessionId: payload.sessionId || 'anonymous',
    currentEmotion: payload.currentEmotion || 'CONFUSION',
    score,
    timestamp: payload.timestamp || new Date().toISOString(),
    confusionScore: metadata.confusionScore ?? score,
    fatigueScore: metadata.fatigueScore,
    confusionAverage: metadata.confusionAverage,
    highConfusionFrames: metadata.highConfusionFrames,
    windowSize: metadata.windowSize,
    idleMs: metadata.idleMs,
    state: metadata.state || payload.currentEmotion || 'CONFUSION',
    rawBlendshapes: metadata.rawBlendshapes,
    triggeredAt: new Date().toISOString(),
  })
}

function buildPayloadText(payload) {
  const emotion = payload.currentEmotion || 'CONFUSION'
  const score = Math.max(0, Math.min(1, Number(payload.score) || 0))
  return (
    payload.summary
    || `Sustained ${emotion.toLowerCase()} detected during learning (confidence ${score.toFixed(2)}).`
  )
}

/**
 * Invoke emotion intervention via POST /api/ai/invoke (AgentInvokeRequest).
 *
 * @typedef {Object} EmotionInterventionPayload
 * @property {string} [sessionId]
 * @property {('CONFUSION'|'FATIGUE')} currentEmotion
 * @property {number} score
 * @property {string} [timestamp]
 * @property {string} [summary]
 * @property {string} [voiceToken]
 * @property {string} [studentProfileSnapshot]
 * @property {Object} [metadata]
 */
export function requestEmotionIntervention(payload) {
  return invokeAiAgent({
    taskType: 'EMOTION_PROFILE',
    sensoryTags: 'face,emotion,intervention',
    payloadText: buildPayloadText(payload),
    facialToken: buildFacialToken(payload),
    voiceToken: payload.voiceToken || '',
    studentProfileSnapshot: payload.studentProfileSnapshot || '',
  })
}

/**
 * User-initiated confusion intervention (same /api/ai/invoke path).
 */
export function requestConfusionIntervention(payload) {
  return requestEmotionIntervention({
    sessionId: payload.sessionId,
    currentEmotion: 'CONFUSION',
    score: payload.confusionAverage ?? 0,
    summary: payload.summary,
    voiceToken: payload.voiceToken,
    studentProfileSnapshot: payload.studentProfileSnapshot,
    metadata: {
      state: 'CONFUSED',
      confusionScore: payload.confusionAverage,
      confusionAverage: payload.confusionAverage,
      highConfusionFrames: payload.highConfusionFrames,
      windowSize: payload.windowSize,
      idleMs: payload.idleMs,
    },
  })
}
