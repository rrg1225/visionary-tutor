import { defineStore } from 'pinia'
import { extractLearnerProfile } from '../api/profile'
import { fetchLearnerState } from '../api/learner'
import { useAuthStore } from './authStore'

const STORAGE_KEY_PREFIX = 'visionary-tutor-user-profile'

export function resolveProfileStorageKey(userId, isGuest) {
  if (isGuest) return `${STORAGE_KEY_PREFIX}:guest:${userId || 'anonymous'}`
  if (userId) return `${STORAGE_KEY_PREFIX}:user:${userId}`
  return `${STORAGE_KEY_PREFIX}:anonymous`
}

function loadPersistedProfile(storageKey) {
  try {
    const raw = localStorage.getItem(storageKey)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

const PENDING_DIMENSION_MARKERS = new Set(['', '待观察'])

export function isFilledProfileDimensionValue(value) {
  const text = String(value ?? '').trim()
  if (PENDING_DIMENSION_MARKERS.has(text)) return false
  const parts = text.split('/').map((part) => part.trim())
  if (parts.length > 1 && parts.every((part) => PENDING_DIMENSION_MARKERS.has(part))) {
    return false
  }
  return true
}

export function isPendingProfileDimensionValue(value) {
  return !isFilledProfileDimensionValue(value)
}

function applySavedState(target, saved) {
  if (!saved) return
  Object.assign(target, {
    name: saved.name ?? '',
    email: saved.email ?? '',
    focus: saved.focus ?? target.focus,
    style: saved.style ?? target.style,
    goal: saved.goal ?? '',
    knowledgeBase: saved.knowledgeBase ?? target.knowledgeBase,
    cognitiveStyle: saved.cognitiveStyle ?? target.cognitiveStyle,
    weakPoints: Array.isArray(saved.weakPoints) ? saved.weakPoints : [],
    errorPatterns: Array.isArray(saved.errorPatterns) ? saved.errorPatterns : [],
    learningPace: saved.learningPace ?? target.learningPace,
    emotionState: saved.emotionState ?? target.emotionState,
    attentionState: saved.attentionState ?? target.attentionState,
    lastDialogueSummary: saved.lastDialogueSummary ?? '',
    profileSnapshot: saved.profileSnapshot ?? null,
    extractionStatus: saved.extractionStatus ?? 'IDLE',
    extractionMessage: saved.extractionMessage ?? '',
    lastChangedDimensions: Array.isArray(saved.lastChangedDimensions) ? saved.lastChangedDimensions : [],
    updatedAt: saved.updatedAt ?? null,
    isComplete: Boolean(saved.isComplete),
    onboardingComplete: Boolean(saved.onboardingComplete ?? saved.isComplete),
    onboardingSkipped: Boolean(saved.onboardingSkipped),
    onboardingAnswerCount: Number(saved.onboardingAnswerCount ?? 0),
    pendingPostOnboardingGeneration: Boolean(saved.pendingPostOnboardingGeneration),
    profileVersion: Number(saved.profileVersion ?? 0),
    pathVersion: Number(saved.pathVersion ?? 0),
    lastPolicyReason: saved.lastPolicyReason ?? '',
    lastQuizAccuracy: saved.lastQuizAccuracy ?? null,
    profileSyncNotice: saved.profileSyncNotice ?? '',
    teacherTone: saved.teacherTone ?? target.teacherTone,
    responseDetail: saved.responseDetail ?? target.responseDetail,
    answerStructure: saved.answerStructure ?? target.answerStructure,
    encouragementLevel: saved.encouragementLevel ?? target.encouragementLevel,
    emojiUsage: saved.emojiUsage ?? target.emojiUsage,
    teacherVoice: saved.teacherVoice ?? target.teacherVoice,
    emotionSupportEnabled: saved.emotionSupportEnabled ?? target.emotionSupportEnabled,
  })
}

export const useUserProfileStore = defineStore('userProfile', {
  state: () => ({
    name: '',
    email: '',
    focus: '计算机视觉与深度学习',
    style: '图解优先',
    goal: '',
    knowledgeBase: '待观察',
    cognitiveStyle: '待观察',
    weakPoints: [],
    errorPatterns: [],
    learningPace: '待观察',
    emotionState: '待观察',
    attentionState: '待观察',
    lastDialogueSummary: '',
    profileSnapshot: null,
    extractionStatus: 'IDLE',
    extractionMessage: '',
    lastChangedDimensions: [],
    updatedAt: null,
    isComplete: false,
    profileVersion: 0,
    pathVersion: 0,
    lastPolicyReason: '',
    lastQuizAccuracy: null,
    onboardingComplete: false,
    onboardingSkipped: false,
    onboardingAnswerCount: 0,
    pendingPostOnboardingGeneration: false,
    profileSource: 'local',
    profileSyncNotice: '',
    teacherTone: '亲切自然',
    responseDetail: '自适应',
    answerStructure: '先结论后步骤',
    encouragementLevel: '适度',
    emojiUsage: '少量',
    teacherVoice: '温和清晰',
    emotionSupportEnabled: true,
    hydratedStorageKey: null,
  }),
  getters: {
    displayName(state) {
      return state.name.trim() || '学习者'
    },
    isDialogueProfileReady(state) {
      const pending = new Set(['待观察', '待补充', ''])
      const filled = [
        state.knowledgeBase,
        state.goal,
        state.cognitiveStyle,
        state.learningPace,
        state.weakPoints.length ? state.weakPoints.join('、') : '',
        state.errorPatterns.length ? state.errorPatterns.join('、') : '',
      ].filter((value) => !pending.has(String(value).trim())).length
      return filled >= 4 && Boolean(state.goal?.trim())
    },
    isProfileComplete(state) {
      return Boolean(state.onboardingComplete)
    },
    recentQuizLow(state) {
      return state.lastQuizAccuracy != null && state.lastQuizAccuracy < 0.6
    },
    aiTeacherPreferences(state) {
      return {
        tone: state.teacherTone,
        detail: state.responseDetail,
        structure: state.answerStructure,
        encouragement: state.encouragementLevel,
        emojiUsage: state.emojiUsage,
        voice: state.teacherVoice,
        emotionSupportEnabled: state.emotionSupportEnabled,
      }
    },
    profileDimensions(state) {
      return [
        { key: 'knowledgeBase', label: '知识基础', value: state.knowledgeBase },
        { key: 'goal', label: '学习目标', value: state.goal || '待补充' },
        { key: 'cognitiveStyle', label: '认知风格', value: state.cognitiveStyle },
        { key: 'weakPoints', label: '薄弱点', value: state.weakPoints.length ? state.weakPoints.join('、') : '待观察' },
        { key: 'errorPatterns', label: '易错类型', value: state.errorPatterns.length ? state.errorPatterns.join('、') : '待观察' },
        { key: 'learningPace', label: '学习节奏', value: state.learningPace },
        { key: 'emotionAttention', label: '情绪/专注状态', value: `${state.emotionState} / ${state.attentionState}` },
      ]
    },
    filledDimensionCount() {
      return this.profileDimensions.filter((item) => isFilledProfileDimensionValue(item.value)).length
    },
  },
  actions: {
    hydrateFromStorage() {
      const authStore = useAuthStore()
      const storageKey = resolveProfileStorageKey(authStore.currentUserId, authStore.isGuest)
      if (this.hydratedStorageKey !== storageKey) {
        this.$reset()
        this.hydratedStorageKey = storageKey
      }
      applySavedState(this, loadPersistedProfile(storageKey))
    },
    async bootstrapSession() {
      const authStore = useAuthStore()
      this.hydrateFromStorage()
      this.syncAccountFromAuth()

      if (authStore.isRegistered && authStore.currentUserId) {
        const storageKey = resolveProfileStorageKey(authStore.currentUserId, false)
        const cached = loadPersistedProfile(storageKey)
        const cachedVersion = Number(cached?.profileVersion ?? 0)
        applySavedState(this, cached)
        await this.syncFromLearnerOsBackend()
        if (this.profileSource === 'backend' && this.profileVersion > cachedVersion) {
          this.profileSyncNotice = '学习画像已更新'
        }
        this.persistProfile()
        return
      }

      this.hydrateFromStorage()
      this.profileSource = 'local'
    },
    clearProfileSyncNotice() {
      this.profileSyncNotice = ''
      this.persistProfile()
    },
    applyLearnerOsState(state) {
      if (!state) return
      const backendVersion = Number(state.profileVersion ?? 0)
      const shouldApplySnapshot = Boolean(state.profileSnapshot)
        && (backendVersion >= this.profileVersion || this.profileSource !== 'backend')

      if (shouldApplySnapshot) {
        this.applyProfileSnapshot(state.profileSnapshot)
        if (state.learningGoal) this.goal = state.learningGoal
        this.profileSource = 'backend'
      }

      if (backendVersion >= this.profileVersion) {
        this.profileVersion = backendVersion
      }
      this.pathVersion = Number(state.pathVersion ?? this.pathVersion)
      this.lastPolicyReason = state.lastPolicyReason ?? this.lastPolicyReason
      this.persistProfile()
    },
    async syncFromLearnerOsBackend(userId) {
      const authStore = useAuthStore()
      const resolvedUserId = userId ?? (authStore.isRegistered ? authStore.currentUserId : null)
      if (!resolvedUserId) return
      this.hydrateFromStorage()
      try {
        const state = await fetchLearnerState({ silent: true })
        if (state.userId && Number(state.userId) !== Number(resolvedUserId)) {
          console.warn('[userProfile] learner state user mismatch, skipped')
          return
        }
        this.applyLearnerOsState(state)
      } catch (error) {
        console.warn('[userProfile] learner OS sync failed:', error?.message || error)
      }
    },
    recordQuizAccuracy(accuracy) {
      this.lastQuizAccuracy = Number(accuracy)
      this.persistProfile()
    },
    requestPostOnboardingGeneration() {
      this.pendingPostOnboardingGeneration = true
      this.persistProfile()
    },
    consumePostOnboardingGeneration() {
      const pending = Boolean(this.pendingPostOnboardingGeneration)
      this.pendingPostOnboardingGeneration = false
      this.persistProfile()
      return pending
    },
    syncAccountFromAuth() {
      const authStore = useAuthStore()
      if (authStore.user?.email) {
        this.email = authStore.user.email
      }
      const display = authStore.displayName
      if (display && display !== '游客' && display !== '用户') {
        this.name = display
      }
      this.persistProfile()
    },
    saveAccount(payload) {
      this.name = payload.name?.trim() ?? ''
      this.email = payload.email?.trim() ?? ''
      this.focus = payload.focus ?? this.focus
      this.persistProfile()
    },
    saveAiTeacherPreferences(payload = {}) {
      this.teacherTone = payload.teacherTone ?? this.teacherTone
      this.responseDetail = payload.responseDetail ?? this.responseDetail
      this.answerStructure = payload.answerStructure ?? this.answerStructure
      this.encouragementLevel = payload.encouragementLevel ?? this.encouragementLevel
      this.emojiUsage = payload.emojiUsage ?? this.emojiUsage
      this.teacherVoice = payload.teacherVoice ?? this.teacherVoice
      this.emotionSupportEnabled = payload.emotionSupportEnabled ?? this.emotionSupportEnabled
      this.persistProfile()
    },
    recordOnboardingAnswer() {
      this.onboardingAnswerCount += 1
      this.persistProfile()
    },
    restartOnboarding() {
      this.$patch({
        goal: '',
        knowledgeBase: '待观察',
        cognitiveStyle: '待观察',
        weakPoints: [],
        errorPatterns: [],
        learningPace: '待观察',
        emotionState: '待观察',
        attentionState: '待观察',
        lastDialogueSummary: '',
        profileSnapshot: null,
        extractionStatus: 'IDLE',
        extractionMessage: '',
        lastChangedDimensions: [],
        updatedAt: null,
        isComplete: false,
        onboardingComplete: false,
        onboardingSkipped: false,
        onboardingAnswerCount: 0,
        pendingPostOnboardingGeneration: false,
        profileSource: 'local',
        profileSyncNotice: '',
      })
      this.persistProfile()
    },
    markOnboardingComplete() {
      this.onboardingComplete = true
      this.onboardingSkipped = false
      this.isComplete = true
      this.persistProfile()
    },
    deferOnboarding() {
      this.onboardingComplete = true
      this.onboardingSkipped = true
      this.isComplete = true
      this.extractionStatus = 'DEFERRED'
      this.extractionMessage = '已跳过首次建档；系统会根据后续对话、测评和练习逐步完善画像。'
      this.persistProfile()
    },
    saveProfile(payload) {
      this.name = payload.name?.trim() ?? ''
      this.email = payload.email?.trim() ?? ''
      this.focus = payload.focus ?? this.focus
      this.style = payload.style ?? this.style
      this.goal = payload.goal?.trim() ?? ''
      this.knowledgeBase = payload.knowledgeBase ?? this.knowledgeBase
      this.cognitiveStyle = payload.cognitiveStyle ?? payload.style ?? this.cognitiveStyle
      this.weakPoints = normalizeConceptList(payload.weakPoints, this.weakPoints)
      this.errorPatterns = normalizeConceptList(payload.errorPatterns, this.errorPatterns)
      this.learningPace = payload.learningPace ?? this.learningPace
      this.emotionState = payload.emotionState ?? this.emotionState
      this.attentionState = payload.attentionState ?? this.attentionState
      this.persistProfile()
    },
    async updateFromDialogue(text) {
      if (!text?.trim()) return
      await this.extractFromLearningContext({ conversationText: text })
    },
    async applyAssessmentResult(result) {
      const data = result?.data ?? result
      await this.extractFromLearningContext({
        assessmentSummary: [
          data?.ocrText || data?.ocr_text || '',
          data?.errorPattern || data?.error_pattern || '',
          data?.correctiveFeedback || data?.corrective_feedback || '',
        ].filter(Boolean).join('\n'),
      })
    },
    async extractFromLearningContext({
      conversationText = '',
      assessmentSummary = '',
      emotionSnapshot = '',
      extractPhase = 'FULL',
    } = {}) {
      const authStore = useAuthStore()
      this.extractionStatus = 'RUNNING'
      this.extractionMessage = extractPhase === 'USER_TURN'
        ? '正在根据你的新发言更新画像…'
        : '正在用大模型更新学习画像'
      try {
        const response = await extractLearnerProfile({
          userId: authStore.isRegistered ? Number(authStore.currentUserId) : null,
          conversationText,
          assessmentSummary,
          previousProfileSnapshot: this.profileSnapshot ? JSON.stringify(this.profileSnapshot) : '',
          emotionSnapshot,
          extractPhase,
        })
        this.extractionStatus = response.status || 'UPDATED'
        this.extractionMessage = response.message || ''
        this.lastDialogueSummary = (conversationText || assessmentSummary).slice(0, 160)
        this.updatedAt = new Date().toISOString()
        this.applyProfileSnapshot(response.profileSnapshot)
        this.persistProfile()
        if (authStore.isRegistered && authStore.currentUserId) {
          await this.syncFromLearnerOsBackend(authStore.currentUserId)
        }
      } catch (error) {
        this.extractionStatus = 'FAILED'
        this.extractionMessage = '本轮画像将在后续学习记录中继续更新'
        this.persistProfile()
      }
    },
    applyProfileSnapshot(rawSnapshot) {
      if (!rawSnapshot) return
      const previousDimensions = this.profileDimensions.map((item) => ({ key: item.key, value: item.value }))
      let snapshot
      try {
        snapshot = typeof rawSnapshot === 'string' ? JSON.parse(rawSnapshot) : rawSnapshot
      } catch {
        this.extractionStatus = 'FAILED'
        this.extractionMessage = '画像 JSON 解析失败'
        return
      }
      if (!snapshot || typeof snapshot !== 'object') return
      this.profileSnapshot = snapshot
      this.knowledgeBase = valueOf(snapshot.knowledgeBase, this.knowledgeBase)
      this.goal = valueOf(snapshot.goal, this.goal)
      this.cognitiveStyle = valueOf(snapshot.cognitiveStyle, this.cognitiveStyle)
      this.weakPoints = normalizeConceptList(snapshot.weakPoints?.value ?? snapshot.weakPoints, this.weakPoints)
      this.errorPatterns = normalizeConceptList(snapshot.errorPatterns?.value ?? snapshot.errorPatterns, this.errorPatterns)
      this.learningPace = valueOf(snapshot.learningPace, this.learningPace)
      const emotion = valueOf(snapshot.emotionAttention, '')
      if (emotion.includes('/')) {
        const [emotionState, attentionState] = emotion.split('/').map((item) => item.trim())
        this.emotionState = emotionState || this.emotionState
        this.attentionState = attentionState || this.attentionState
      } else if (emotion) {
        this.emotionState = emotion
      }

      const nextDimensions = this.profileDimensions
      this.lastChangedDimensions = nextDimensions
        .filter((item) => {
          const prev = previousDimensions.find((entry) => entry.key === item.key)
          return prev && prev.value !== item.value
        })
        .map((item) => item.label)
    },
    persistProfile() {
      const authStore = useAuthStore()
      const storageKey = resolveProfileStorageKey(authStore.currentUserId, authStore.isGuest)
      localStorage.setItem(storageKey, JSON.stringify({
        name: this.name,
        email: this.email,
        focus: this.focus,
        style: this.style,
        goal: this.goal,
        knowledgeBase: this.knowledgeBase,
        cognitiveStyle: this.cognitiveStyle,
        weakPoints: this.weakPoints,
        errorPatterns: this.errorPatterns,
        learningPace: this.learningPace,
        emotionState: this.emotionState,
        attentionState: this.attentionState,
        lastDialogueSummary: this.lastDialogueSummary,
        profileSnapshot: this.profileSnapshot,
        extractionStatus: this.extractionStatus,
        extractionMessage: this.extractionMessage,
        lastChangedDimensions: this.lastChangedDimensions,
        updatedAt: this.updatedAt,
        isComplete: this.isComplete,
        onboardingComplete: this.onboardingComplete,
        onboardingSkipped: this.onboardingSkipped,
        onboardingAnswerCount: this.onboardingAnswerCount,
        pendingPostOnboardingGeneration: this.pendingPostOnboardingGeneration,
        profileVersion: this.profileVersion,
        pathVersion: this.pathVersion,
        lastPolicyReason: this.lastPolicyReason,
        lastQuizAccuracy: this.lastQuizAccuracy,
        profileSource: this.profileSource,
        profileSyncNotice: this.profileSyncNotice,
        teacherTone: this.teacherTone,
        responseDetail: this.responseDetail,
        answerStructure: this.answerStructure,
        encouragementLevel: this.encouragementLevel,
        emojiUsage: this.emojiUsage,
        teacherVoice: this.teacherVoice,
        emotionSupportEnabled: this.emotionSupportEnabled,
      }))
    },
  },
})

function valueOf(node, fallback = '待观察') {
  if (node == null) return fallback
  if (typeof node === 'string') return node || fallback
  if (Array.isArray(node)) return node.join('、') || fallback
  return node.value == null ? fallback : Array.isArray(node.value) ? node.value.join('、') : String(node.value)
}

function arrayValueOf(node, fallback = []) {
  const value = node?.value ?? node
  if (Array.isArray(value)) return value.map(String).filter(Boolean)
  if (typeof value === 'string' && value.trim()) {
    return value.split(/[、，,\n]/).map((item) => item.trim()).filter(Boolean)
  }
  return fallback
}

function normalizeList(value, fallback = []) {
  if (Array.isArray(value)) return value.filter(Boolean).map((item) => String(item).trim()).filter(Boolean)
  if (typeof value === 'string') return value.split(/[、，,\n]/).map((item) => item.trim()).filter(Boolean)
  return fallback
}

function normalizeConceptList(value, fallback = []) {
  return normalizeList(value, fallback)
    .map((item) => String(item).trim())
    .filter((item) => item && item !== '待观察' && item !== 'unspecified' && item.length <= 60)
    .filter((item) => !/[。！？!?]/.test(item))
    .filter((item) => !/(建议|推荐|请先|请完成|可以通过|下一步|继续学习|上传|重试|点击|生成资源)/.test(item))
    .slice(0, 8)
}
