import { FaceLandmarker, FilesetResolver } from '@mediapipe/tasks-vision'
import { resetFatigueTracker } from '../utils/emotionClassifier.js'

/**
 * Local WASM and model paths for offline competition demos.
 */
const WASM_PATH = '/models'
const MODEL_ASSET_PATH = '/models/face_landmarker.task'

/** @type {Promise<import('@mediapipe/tasks-vision').FaceLandmarker> | null} */
let enginePromise = null

/**
 * Singleton Face Landmarker engine — shared by FaceCaptureStream detection loop only.
 * Backend emotion calls are handled in useConfusionIntervention (no duplicate path here).
 */
export function getFaceLandmarkerEngine() {
  if (!enginePromise) {
    enginePromise = createEngine().catch((error) => {
      enginePromise = null
      throw error
    })
  }
  return enginePromise
}

async function createEngine() {
  const filesetResolver = await FilesetResolver.forVisionTasks(WASM_PATH)
  return FaceLandmarker.createFromOptions(filesetResolver, {
    baseOptions: {
      modelAssetPath: MODEL_ASSET_PATH,
      delegate: 'GPU',
    },
    outputFaceBlendshapes: true,
    runningMode: 'VIDEO',
    numFaces: 1,
  })
}

export function releaseFaceLandmarkerEngine() {
  enginePromise = null
  resetFatigueTracker()
}
