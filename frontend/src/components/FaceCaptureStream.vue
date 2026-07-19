<template>
  <div class="face-capture">
    <!-- Camera Surface -->
    <div
      class="camera-surface"
      :class="{
        'camera-ready': cameraReady,
        'camera-error': cameraError,
        'camera-initializing': !cameraReady && !cameraError,
      }"
    >
      <!-- Video Element -->
      <video
        ref="videoRef"
        class="camera-video"
        autoplay
        muted
        playsinline
        :class="{ 'video-visible': cameraReady }"
      ></video>

      <!-- Initializing State -->
      <Transition name="fade-scale">
        <div v-if="!cameraReady && !cameraError" class="camera-overlay camera-loading">
          <div class="loading-spinner">
            <div class="spinner-ring"></div>
            <div class="spinner-ring spinner-ring-delayed"></div>
          </div>
          <p class="loading-text">正在初始化摄像头与面部识别引擎...</p>
          <p class="loading-subtext">首次加载可能需要几秒钟</p>
        </div>
      </Transition>

      <!-- Error State -->
      <Transition name="fade">
        <div v-if="cameraError" class="camera-overlay camera-error-overlay">
          <div class="error-icon">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="12" cy="12" r="10" />
              <path d="M15 9l-6 6M9 9l6 6" />
            </svg>
          </div>
          <p class="error-title">摄像头访问失败</p>
          <p class="error-message">{{ cameraError }}</p>
          <button v-if="cameraRetryable" class="vt-btn vt-btn-primary retry-btn" @click="retryCamera">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M23 4v6h-6M1 20v-6h6M3.51 9a9 9 0 0114.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0020.49 15" />
            </svg>
            重试
          </button>
        </div>
      </Transition>

      <!-- Status Indicators (shown when ready) -->
      <Transition name="fade">
        <div v-if="cameraReady && monitoringActive" class="camera-status-bar">
          <div class="status-indicator" :class="{ 'status-active': isDetecting }">
            <span class="status-pulse"></span>
            <span class="status-text">{{ isDetecting ? '监测中' : '待检测' }}</span>
          </div>
          <div v-if="confusionScore > 0" class="status-confidence">
            <span class="confidence-label">困惑指数</span>
            <div class="confidence-bar">
              <div class="confidence-fill" :style="{ width: `${confusionScore}%` }"></div>
            </div>
            <span class="confidence-value">{{ Math.round(confusionScore) }}%</span>
          </div>
        </div>
      </Transition>

      <!-- Face Detection Frame (decorative) -->
      <Transition name="fade">
        <div v-if="cameraReady" class="face-frame">
          <div class="face-frame-corner face-frame-tl"></div>
          <div class="face-frame-corner face-frame-tr"></div>
          <div class="face-frame-corner face-frame-bl"></div>
          <div class="face-frame-corner face-frame-br"></div>
          <div class="face-frame-center">
            <div class="scan-line" :class="{ 'scanning': isDetecting }"></div>
          </div>
        </div>
      </Transition>
    </div>

    <!-- Accessibility Live Region -->
    <p v-if="monitoringActive" class="sr-only" aria-live="polite">
      面部困惑监测正在后台运行
    </p>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'
import { getFaceLandmarkerEngine } from '../services/faceLandmarkerEngine'
import { usePageIdleTracker } from '../composables/usePageIdleTracker'
import { useConfusionIntervention } from '../composables/useConfusionIntervention'
import { acquireUserCameraStream, normalizeCameraAccessError, stopMediaStream } from '../utils/camera'
import { useUserProfileStore } from '../stores/userProfile'
import { serializeProfileSnapshot } from '../utils/profileSnapshot'

const props = defineProps({
  enabled: {
    type: Boolean,
    default: true,
  },
})

const emit = defineEmits(['intervention', 'sample', 'ready', 'error'])

// ==================== Refs ====================
const videoRef = ref(null)
const cameraReady = ref(false)
const cameraError = ref('')
const cameraRetryable = ref(true)
const monitoringActive = ref(false)
const confusionScore = ref(0)

// ==================== State ====================
let mediaStream = null
let faceLandmarker = null
let animationFrameId = 0
let lastVideoTime = -1
// ==================== Composables ====================
const { getIdleMs, startListening } = usePageIdleTracker()
const userProfile = useUserProfileStore()

const { processBlendshapes, isDetecting, dispose: disposeConfusionIntervention } = useConfusionIntervention({
  getIdleMs,
  getStudentProfileSnapshot: () => serializeProfileSnapshot(
    userProfile.profileSnapshot,
    userProfile.profileDimensions,
  ),
  onIntervention: (result) => emit('intervention', result),
})

function syncProfileFromVision(scorePercent) {
  if (scorePercent >= 60) {
    userProfile.emotionState = '困惑'
    userProfile.attentionState = '认知负荷偏高'
  } else if (scorePercent >= 30) {
    userProfile.emotionState = '专注思考'
    userProfile.attentionState = '中等专注'
  } else {
    userProfile.emotionState = '平稳'
    userProfile.attentionState = '专注'
  }
  userProfile.persistProfile()
}

// ==================== Methods ====================
async function initCamera() {
  try {
    cameraError.value = ''
    cameraRetryable.value = true
    mediaStream = await acquireUserCameraStream()
    const video = videoRef.value
    if (!video) return

    video.srcObject = mediaStream
    await video.play()
    cameraReady.value = true
    emit('ready')
  } catch (error) {
    const normalizedError = normalizeCameraAccessError(error)
    cameraError.value = normalizedError.message
    cameraRetryable.value = normalizedError.retryable
    emit('error', normalizedError.message)
  }
}

async function initEngine() {
  try {
    faceLandmarker = await getFaceLandmarkerEngine()
    monitoringActive.value = true
  } catch (error) {
    cameraError.value =
      error?.message || '面部识别引擎初始化失败'
  }
}

function predictWebcam() {
  const video = videoRef.value
  if (!faceLandmarker || !video || video.videoWidth === 0) {
    animationFrameId = requestAnimationFrame(predictWebcam)
    return
  }

  const startTimeMs = performance.now()
  if (lastVideoTime !== video.currentTime) {
    lastVideoTime = video.currentTime
    const results = faceLandmarker.detectForVideo(video, startTimeMs)
    const blendshapes = results.faceBlendshapes?.[0]?.categories

    if (blendshapes?.length) {
      processBlendshapes(blendshapes)

      // Calculate visual confusion score for display
      const confusionMetrics = blendshapes.filter(
        (b) => ['browDownLeft', 'browDownRight', 'squintLeft', 'squintRight'].includes(b.categoryName)
      )
      const avgConfusion =
        confusionMetrics.reduce((sum, m) => sum + m.score, 0) / (confusionMetrics.length || 1)
      confusionScore.value = Math.min(Math.round(avgConfusion * 100), 100)
      emit('sample', {
        confusionScore: confusionScore.value,
        observedAt: new Date().toISOString(),
        signalCount: blendshapes.length,
      })
    }
  }

  animationFrameId = requestAnimationFrame(predictWebcam)
}

function retryCamera() {
  cameraReady.value = false
  cameraError.value = ''
  initCamera().then(() => {
    if (cameraReady.value && !faceLandmarker) {
      initEngine().then(() => {
        predictWebcam()
      })
    }
  })
}

// ==================== Lifecycle ====================
onMounted(async () => {
  if (!props.enabled) {
    return
  }
  userProfile.hydrateFromStorage()
  startListening()
  await initCamera()

  if (cameraReady.value) {
    try {
      await initEngine()
      predictWebcam()
    } catch (error) {
      cameraError.value = error?.message || 'MediaPipe Face Landmarker 初始化失败'
    }
  }
})

onUnmounted(() => {
  disposeConfusionIntervention?.()
  if (animationFrameId) {
    cancelAnimationFrame(animationFrameId)
  }
  stopMediaStream(mediaStream)
  mediaStream = null
  if (videoRef.value) {
    videoRef.value.srcObject = null
  }
})
</script>

<style scoped>
/* Face Capture Container */
.face-capture {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-4);
}

/* Camera Surface */
.camera-surface {
  position: relative;
  width: 100%;
  aspect-ratio: 4 / 3;
  background: linear-gradient(
    145deg,
    rgba(30, 41, 59, 0.98) 0%,
    rgba(15, 23, 42, 0.98) 100%
  );
  border-radius: var(--vt-radius-lg);
  overflow: hidden;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.05),
    0 4px 6px -1px rgba(0, 0, 0, 0.1),
    0 2px 4px -1px rgba(0, 0, 0, 0.06);
}

/* Video Element */
.camera-video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  transform: scale(1.02);
  transition: all 0.5s cubic-bezier(0.4, 0, 0.2, 1);
  filter: saturate(0.9) contrast(1.05);
}

.video-visible {
  opacity: 1;
  transform: scale(1);
}

/* Camera Overlay */
.camera-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--vt-space-4);
  padding: var(--vt-space-6);
  text-align: center;
  color: rgba(255, 255, 255, 0.9);
  z-index: 10;
}

/* Loading State */
.loading-spinner {
  position: relative;
  width: 48px;
  height: 48px;
}

.spinner-ring {
  position: absolute;
  inset: 0;
  border: 3px solid transparent;
  border-top-color: var(--vt-accent-teal);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

.spinner-ring-delayed {
  border-top-color: transparent;
  border-right-color: rgba(13, 148, 136, 0.5);
  animation-delay: -0.5s;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.loading-text {
  margin: 0;
  font-size: var(--vt-text-sm);
  font-weight: var(--vt-font-medium);
  color: rgba(255, 255, 255, 0.9);
}

.loading-subtext {
  margin: 0;
  font-size: var(--vt-text-xs);
  color: rgba(255, 255, 255, 0.5);
}

/* Error State */
.camera-error-overlay {
  background: rgba(0, 0, 0, 0.7);
  backdrop-filter: blur(4px);
}

.error-icon {
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--vt-radius-full);
  background: rgba(239, 68, 68, 0.15);
  color: var(--vt-accent-red);
}

.error-icon svg {
  width: 28px;
  height: 28px;
}

.error-title {
  margin: 0;
  font-size: var(--vt-text-base);
  font-weight: var(--vt-font-semibold);
  color: rgba(255, 255, 255, 0.95);
}

.error-message {
  margin: 0;
  font-size: var(--vt-text-sm);
  color: rgba(255, 255, 255, 0.6);
  max-width: 280px;
}

.retry-btn {
  margin-top: var(--vt-space-2);
}

/* Status Bar */
.camera-status-bar {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
  padding: var(--vt-space-3) var(--vt-space-4);
  background: linear-gradient(to top, rgba(0, 0, 0, 0.7), transparent);
  color: white;
  font-size: var(--vt-text-xs);
}

.status-indicator {
  display: flex;
  align-items: center;
  gap: var(--vt-space-2);
}

.status-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.4);
  transition: all 0.3s ease;
}

.status-active .status-pulse {
  background: #22c55e;
  animation: pulse-green 1.5s ease-in-out infinite;
}

@keyframes pulse-green {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.8); }
}

.status-text {
  font-weight: var(--vt-font-medium);
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
}

.status-confidence {
  display: flex;
  align-items: center;
  gap: var(--vt-space-2);
}

.confidence-label {
  opacity: 0.8;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
}

.confidence-bar {
  width: 60px;
  height: 4px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 2px;
  overflow: hidden;
}

.confidence-fill {
  height: 100%;
  background: linear-gradient(to right, #22c55e, #f97316);
  border-radius: 2px;
  transition: width 0.3s ease;
}

.confidence-value {
  min-width: 28px;
  font-weight: var(--vt-font-semibold);
  text-align: right;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.3);
}

/* Face Detection Frame */
.face-frame {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.face-frame-corner {
  position: absolute;
  width: 24px;
  height: 24px;
  border: 2px solid rgba(255, 255, 255, 0.3);
}

.face-frame-tl {
  top: 20%;
  left: 20%;
  border-right: 0;
  border-bottom: 0;
  border-top-left-radius: 4px;
}

.face-frame-tr {
  top: 20%;
  right: 20%;
  border-left: 0;
  border-bottom: 0;
  border-top-right-radius: 4px;
}

.face-frame-bl {
  bottom: 20%;
  left: 20%;
  border-right: 0;
  border-top: 0;
  border-bottom-left-radius: 4px;
}

.face-frame-br {
  bottom: 20%;
  right: 20%;
  border-left: 0;
  border-top: 0;
  border-bottom-right-radius: 4px;
}

.face-frame-center {
  position: absolute;
  inset: 20%;
  overflow: hidden;
}

.scan-line {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(
    to right,
    transparent,
    rgba(13, 148, 136, 0.5),
    transparent
  );
  transform: translateY(-100%);
  opacity: 0;
}

.scanning {
  animation: scan 2s ease-in-out infinite;
  opacity: 1;
}

@keyframes scan {
  0%, 100% { transform: translateY(-100%); opacity: 0; }
  10% { opacity: 1; }
  90% { opacity: 1; }
  100% { transform: translateY(calc(100% * 5)); opacity: 0; }
}

/* Intervention Toast */
.intervention-toast {
  display: flex;
  align-items: center;
  gap: var(--vt-space-3);
  padding: var(--vt-space-4);
  background: var(--vt-surface);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-lg);
  box-shadow: var(--vt-shadow-lg);
}

.toast-icon {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--vt-radius-full);
  background: rgba(13, 148, 136, 0.1);
  color: var(--vt-accent-teal);
  flex-shrink: 0;
}

.toast-icon svg {
  width: 20px;
  height: 20px;
}

.toast-offline {
  background: rgba(245, 158, 11, 0.1);
  color: #d97706;
}

.toast-content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-1);
}

.toast-message {
  margin: 0;
  font-size: var(--vt-text-sm);
  font-weight: var(--vt-font-medium);
  color: var(--vt-text-primary);
}

.toast-tag {
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-accent-orange);
  background: rgba(245, 158, 11, 0.1);
  padding: 2px 8px;
  border-radius: var(--vt-radius-full);
  width: fit-content;
}

.toast-close {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--vt-text-tertiary);
  cursor: pointer;
  border-radius: var(--vt-radius-md);
  transition: all var(--vt-transition-fast);
  padding: 0;
}

.toast-close:hover {
  background: var(--vt-bg-tertiary);
  color: var(--vt-text-primary);
}

.toast-close svg {
  width: 18px;
  height: 18px;
}

/* Transitions */
.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--vt-transition-base);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.fade-scale-enter-active,
.fade-scale-leave-active {
  transition: all var(--vt-transition-slow);
}

.fade-scale-enter-from,
.fade-scale-leave-to {
  opacity: 0;
  transform: scale(0.96);
}

.slide-up-enter-active,
.slide-up-leave-active {
  transition: all var(--vt-transition-spring);
}

.slide-up-enter-from,
.slide-up-leave-to {
  opacity: 0;
  transform: translateY(16px);
}

/* Responsive */
@media (min-width: 768px) {
  .camera-surface {
    aspect-ratio: 16 / 10;
  }
}

@media (max-width: 767px) {
  .camera-surface {
    aspect-ratio: 3 / 4;
  }

  .camera-status-bar {
    padding: var(--vt-space-2);
    font-size: 11px;
  }

  .confidence-bar {
    width: 40px;
  }
}

/* Reduced Motion */
@media (prefers-reduced-motion: reduce) {
  .spinner-ring,
  .status-pulse,
  .scan-line,
  .camera-video {
    animation: none;
    transition: opacity 0.1s ease;
  }

  .video-visible {
    transform: none;
  }
}
</style>
