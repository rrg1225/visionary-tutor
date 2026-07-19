import { defineStore } from 'pinia'
import { useAuthStore } from './authStore'
import {
  activateLearningSession,
  createDiagnosticReport,
  createLearningSession,
  deleteLearningSession,
  listDiagnosticReports,
  listLearningSessionSummaries,
  listLearningSessions,
  startNewLearningSession,
  updateLearningSession,
} from '../api/learning'
import {
  cancelResourceGenerationJob,
  getResourceGenerationJob,
  listGeneratedResources,
  retryResourceGenerationJob,
  startResourceGenerationJob,
} from '../api/resources'
import { serializeProfileSnapshot } from '../utils/profileSnapshot'
import { resolveVideoScriptDisplay, stripAgentPreamble } from '../utils/videoScriptDisplay'

const ACTIVE_SESSION_KEY = 'vt_active_session_id'
const RESOURCE_JOB_KEY = 'vt_resource_generation_job'

function persistActiveSessionId(userId, sessionId) {
  if (!userId || !sessionId) return
  try {
    localStorage.setItem(`${ACTIVE_SESSION_KEY}_${userId}`, String(sessionId))
  } catch {
    // ignore
  }
}

function readActiveSessionId(userId) {
  if (!userId) return null
  try {
    const raw = localStorage.getItem(`${ACTIVE_SESSION_KEY}_${userId}`)
    const parsed = Number(raw)
    return Number.isFinite(parsed) ? parsed : null
  } catch {
    return null
  }
}

function persistResourceJob(userId, taskId, learningSessionId) {
  if (!userId || !taskId || !learningSessionId) return
  try {
    localStorage.setItem(`${RESOURCE_JOB_KEY}_${userId}`, JSON.stringify({ taskId, learningSessionId }))
  } catch {
    // ignore storage restrictions
  }
}

function readResourceJob(userId) {
  if (!userId) return null
  try {
    return JSON.parse(localStorage.getItem(`${RESOURCE_JOB_KEY}_${userId}`) || 'null')
  } catch {
    return null
  }
}

function removeResourceJob(userId) {
  if (!userId) return
  try {
    localStorage.removeItem(`${RESOURCE_JOB_KEY}_${userId}`)
  } catch {
    // ignore storage restrictions
  }
}

export const useLearningSessionStore = defineStore('learningSession', {
  state: () => ({
    currentSession: null,
    currentSessionId: null,
    sessionSummaries: [],
    sessionsLoading: false,
    streamingText: '',
    uploadedAssessment: null,
    assessmentResult: null,
    weakNodes: [],
    diagnosticReport: null,
    generatedResources: [],
    agentSteps: [],
    lastResourceRunId: null,
    resourceReviewSummary: '',
    isGeneratingResources: false,
    resourceGenerationProgress: 0,
    resourceGenerationStatus: '',
    resourceGenerationEtaSeconds: 0,
    resourceGenerationRetryable: false,
    currentResourceTaskId: null,
    lastGeneratedTypes: [],
  }),
  getters: {
    resourceCards(state) {
      if (state.generatedResources.length) {
        return state.generatedResources.map(toResourceCard)
      }
      return []
    },
  },
  actions: {
    setStreamingText(value) {
      this.streamingText = value
      if (this.currentSessionId) {
        updateLearningSession(this.currentSessionId, {
          streamingHandout: value,
          currentPhase: 'RESOURCE_GENERATION',
        }).catch((error) => console.warn('[LearningSession] handout sync skipped:', error?.message || error))
      }
    },
    setUploadedAssessment(file) {
      this.uploadedAssessment = file
    },
    setAssessmentResult(response) {
      this.assessmentResult = response
      const data = response?.data ?? response
      const errorPattern = data?.errorPattern || data?.error_pattern
      const weakPoint = sanitizeWeakPointLabel(errorPattern)
      if (weakPoint) {
        this.weakNodes = [
          { name: weakPoint, layer: '作业评测', mastery: Math.round((data?.confidence ?? 0.55) * 100) },
          ...this.weakNodes.filter((node) => node.name !== weakPoint),
        ].slice(0, 5)
      }
    },
    async ensureCurrentSession(topic = '个性化学习会话') {
      const authStore = useAuthStore()
      const userId = Number(authStore.currentUserId)
      if (!authStore.isRegistered || !Number.isFinite(userId)) {
        return null
      }
      if (this.currentSessionId) {
        return this.currentSession
      }

      await this.refreshSessionSummaries()

      const storedId = readActiveSessionId(userId)
      if (storedId) {
        const stored = this.sessionSummaries.find((item) => item.id === storedId)
          || (await listLearningSessions(userId, { silent: true }).catch(() => []))
            .find((item) => item.id === storedId)
        if (stored) {
          await this.applySession(stored, { activate: stored.status !== 'ACTIVE' })
          return this.currentSession
        }
      }

      const active = this.sessionSummaries.find((item) => item.status === 'ACTIVE')
        || (await listLearningSessions(userId, { silent: true }).catch(() => []))
          .find((item) => item.status === 'ACTIVE')
      if (active) {
        await this.applySession(active)
        return this.currentSession
      }

      if (this.sessionSummaries.length > 0) {
        await this.applySession(this.sessionSummaries[0], { activate: true })
        return this.currentSession
      }

      const created = await createLearningSession({
        userId,
        topic,
        status: 'ACTIVE',
        currentPhase: 'STUDENT_PROFILE',
      })
      await this.applySession(created)
      return created
    },
    async refreshSessionSummaries() {
      const authStore = useAuthStore()
      const userId = Number(authStore.currentUserId)
      if (!authStore.isRegistered || !Number.isFinite(userId)) {
        this.sessionSummaries = []
        return []
      }
      this.sessionsLoading = true
      try {
        const summaries = await listLearningSessionSummaries(userId, { silent: true })
        this.sessionSummaries = Array.isArray(summaries) ? summaries : []
        return this.sessionSummaries
      } catch (error) {
        console.warn('[LearningSession] summaries skipped:', error?.message || error)
        this.sessionSummaries = []
        return []
      } finally {
        this.sessionsLoading = false
      }
    },
    async applySession(session, { activate = false } = {}) {
      if (!session?.id) return null
      const authStore = useAuthStore()
      const userId = Number(authStore.currentUserId)
      let resolved = session
      if (activate && authStore.isRegistered) {
        resolved = await activateLearningSession(session.id, { silent: true }).catch(() => session)
      }
      this.currentSession = resolved
      this.currentSessionId = resolved.id
      persistActiveSessionId(userId, resolved.id)
      this.resetTransientState()
      this.streamingText = resolved.streamingHandout || ''
      await Promise.all([this.hydrateLatestReport(), this.hydrateGeneratedResources()])
      void this.refreshSessionSummaries()
      return resolved
    },
    async switchToSession(sessionId) {
      if (!sessionId || sessionId === this.currentSessionId) {
        return this.currentSession
      }
      const summary = this.sessionSummaries.find((item) => item.id === sessionId)
      if (summary) {
        return this.applySession(summary, { activate: true })
      }
      const authStore = useAuthStore()
      const userId = Number(authStore.currentUserId)
      const sessions = await listLearningSessions(userId, { silent: true }).catch(() => [])
      const found = sessions.find((item) => item.id === sessionId)
      if (!found) {
        throw new Error('会话不存在或已被删除')
      }
      return this.applySession(found, { activate: true })
    },
    async startNewSession(topic = '个性化学习会话') {
      const authStore = useAuthStore()
      const userId = Number(authStore.currentUserId)
      if (!authStore.isRegistered || !Number.isFinite(userId)) {
        throw new Error('请先登录后再新建会话')
      }
      const created = await startNewLearningSession(topic)
      this.resetTransientState()
      this.currentSession = created
      this.currentSessionId = created.id
      persistActiveSessionId(userId, created.id)
      void this.refreshSessionSummaries()
      return created
    },
    async removeSession(sessionId) {
      if (!sessionId) return
      await deleteLearningSession(sessionId)
      const authStore = useAuthStore()
      const userId = Number(authStore.currentUserId)
      if (sessionId === this.currentSessionId) {
        this.currentSession = null
        this.currentSessionId = null
        this.resetTransientState()
        try {
          localStorage.removeItem(`${ACTIVE_SESSION_KEY}_${userId}`)
        } catch {
          // ignore
        }
        await this.refreshSessionSummaries()
        if (this.sessionSummaries.length > 0) {
          await this.applySession(this.sessionSummaries[0], { activate: true })
        }
      } else {
        void this.refreshSessionSummaries()
      }
    },
    async hydrateLatestReport() {
      if (!this.currentSessionId) return null
      const reports = await listDiagnosticReports(this.currentSessionId).catch(() => [])
      const latest = reports?.[0]
      if (!latest) return null
      this.applyDiagnosticReport(latest)
      return latest
    },
    async hydrateGeneratedResources() {
      if (!this.currentSessionId) return []
      const resources = await listGeneratedResources(this.currentSessionId, { silent: true }).catch(() => [])
      this.generatedResources = Array.isArray(resources) ? resources : []
      return this.generatedResources
    },
    applyDiagnosticReport(report) {
      this.diagnosticReport = report
      this.weakNodes = Array.isArray(report?.weakNodes)
        ? report.weakNodes.map((node) => ({
            name: node.nodeName,
            layer: layerLabel(node.knowledgeLayer),
            mastery: node.masteryScore,
          }))
        : []
    },
    async persistAssessmentReport(response, topic = '作业视觉评测') {
      const session = await this.ensureCurrentSession(topic)
      if (!session?.id || !response) {
        return null
      }
      const data = response?.data ?? response
      const report = await createDiagnosticReport({
        learningSessionId: session.id,
        diagnosisId: response.requestId || `assessment-${Date.now()}`,
        reasoningTrace: data.correctiveFeedback || data.corrective_feedback || response.message || '',
        ragApplicationContext: data.ocrText || data.ocr_text || '',
        ragAlgorithmContext: data.errorPattern || data.error_pattern || '',
        ragMathContext: '',
        weakNodes: buildWeakNodesFromAssessment(data),
      })
      this.applyDiagnosticReport(report)
      await updateLearningSession(session.id, {
        currentPhase: 'ASSESSMENT_FEEDBACK',
        assessmentFileName: this.uploadedAssessment?.name || null,
      }).catch(() => null)
      return report
    },
    async generatePersonalizedResources({ topic, profileSnapshot, weakPointsSnapshot, emotionSnapshot, resourceTypes } = {}) {
      const authStore = useAuthStore()
      const userId = Number(authStore.currentUserId)
      const session = await this.ensureCurrentSession(topic || '个性化资源生成')
      if (!session?.id) {
        throw new Error('无法创建学习会话，请确认已登录')
      }
      const requestedTypes = Array.isArray(resourceTypes) && resourceTypes.length > 0
        ? [...resourceTypes]
        : []
      this.isGeneratingResources = true
      this.resourceGenerationProgress = 0
      this.resourceGenerationStatus = '准备资源生成...'
      this.resourceGenerationEtaSeconds = 180
      this.resourceGenerationRetryable = false
      this.agentSteps = []
      try {
        const payload = {
          learningSessionId: session.id,
          requestId: globalThis.crypto?.randomUUID?.()
            || `generation-${Date.now()}-${Math.random().toString(16).slice(2)}`,
          topic: topic || session.topic,
          learnerProfileSnapshot: serializeProfileSnapshot(profileSnapshot) || '',
          weakPointsSnapshot: weakPointsSnapshot || this.weakNodes.map((node) => node.name).join('、'),
          emotionSnapshot: emotionSnapshot || '',
        }
        if (requestedTypes.length > 0) {
          payload.resourceTypes = requestedTypes
        }
        const started = await startResourceGenerationJob(payload)
        this.currentResourceTaskId = started.taskId
        persistResourceJob(userId, started.taskId, session.id)
        const response = await this.waitForResourceGenerationJob(started.taskId)

        await this.completeResourceGeneration(response, requestedTypes)
        const handout = this.generatedResources.find((item) => item.artifactType === 'HANDOUT')
        if (handout?.contentMarkdown) {
          this.streamingText = handout.contentMarkdown
        }
        if (topic && session.id) {
          updateLearningSession(session.id, { topic }).catch(() => null)
        }
        return response
      } finally {
        this.isGeneratingResources = false
      }
    },
    async waitForResourceGenerationJob(taskId) {
      for (let attempt = 0; attempt < 900; attempt += 1) {
        const snapshot = await getResourceGenerationJob(taskId)
        this.applyResourceJobSnapshot(snapshot)
        if (snapshot.status === 'SUCCEEDED') {
          const authStore = useAuthStore()
          removeResourceJob(Number(authStore.currentUserId))
          this.currentResourceTaskId = null
          return snapshot.response
        }
        if (snapshot.status === 'FAILED' || snapshot.status === 'CANCELED' || snapshot.status === 'NOT_FOUND') {
          const authStore = useAuthStore()
          removeResourceJob(Number(authStore.currentUserId))
          this.currentResourceTaskId = snapshot.status === 'NOT_FOUND' ? null : taskId
          const message = snapshot.status === 'CANCELED'
            ? '资源生成任务已取消'
            : (snapshot.message || snapshot.error || '资源生成失败，可重试')
          throw new Error(message)
        }
        await new Promise((resolve) => window.setTimeout(resolve, 1000))
      }
      this.resourceGenerationRetryable = true
      throw new Error('资源生成超时，任务仍可能在后台运行，请稍后恢复或重试')
    },
    applyResourceJobSnapshot(snapshot) {
      if (!snapshot) return
      this.currentResourceTaskId = snapshot.taskId || this.currentResourceTaskId
      this.resourceGenerationProgress = Number(snapshot.progressPercent || 0)
      this.resourceGenerationStatus = snapshot.message || '资源生成中…'
      this.resourceGenerationEtaSeconds = Number(snapshot.estimatedRemainingSeconds || 0)
      this.resourceGenerationRetryable = Boolean(snapshot.retryable)
      const events = Array.isArray(snapshot.events) ? snapshot.events : []
      this.agentSteps = events
        .filter((event) => event.phase === 'agent_step' || event.agentName)
        .map((event) => ({
          id: `${event.runId}-${event.stepOrder}-${event.agentName}`,
          agentName: event.agentName,
          stepOrder: event.stepOrder,
          outputSummary: event.message,
          critique: event.detail,
        }))
    },
    async completeResourceGeneration(response, requestedTypes = []) {
      if (Array.isArray(response?.artifacts) && response.artifacts.length > 0) {
        this.mergeGeneratedResources(response.artifacts)
      } else {
        await this.hydrateGeneratedResources()
      }
      this.agentSteps = response?.steps || this.agentSteps
      this.lastResourceRunId = response?.runId || this.lastResourceRunId
      this.resourceReviewSummary = response?.reviewSummary || ''
      this.resourceGenerationProgress = 100
      this.resourceGenerationEtaSeconds = 0
      this.resourceGenerationRetryable = false
      this.resourceGenerationStatus = '资源生成完成'
      this.lastGeneratedTypes = requestedTypes.length
        ? requestedTypes
        : (response?.artifacts || []).map((item) => item.artifactType).filter(Boolean)
    },
    async resumeResourceGeneration() {
      const authStore = useAuthStore()
      const userId = Number(authStore.currentUserId)
      const saved = readResourceJob(userId)
      if (!saved?.taskId || Number(saved.learningSessionId) !== Number(this.currentSessionId)) {
        return false
      }
      this.currentResourceTaskId = saved.taskId
      this.isGeneratingResources = true
      this.resourceGenerationStatus = '正在恢复后台生成任务…'
      try {
        const response = await this.waitForResourceGenerationJob(saved.taskId)
        await this.completeResourceGeneration(response)
        return true
      } catch (error) {
        this.resourceGenerationStatus = error?.message || '后台生成任务恢复失败'
        return false
      } finally {
        this.isGeneratingResources = false
      }
    },
    async cancelResourceGeneration() {
      if (!this.currentResourceTaskId) return false
      const snapshot = await cancelResourceGenerationJob(this.currentResourceTaskId)
      this.applyResourceJobSnapshot(snapshot)
      const authStore = useAuthStore()
      removeResourceJob(Number(authStore.currentUserId))
      this.isGeneratingResources = false
      return snapshot.status === 'CANCELED'
    },
    async retryResourceGeneration() {
      if (!this.currentResourceTaskId || !this.resourceGenerationRetryable) return false
      const authStore = useAuthStore()
      const snapshot = await retryResourceGenerationJob(this.currentResourceTaskId)
      this.currentResourceTaskId = snapshot.taskId
      persistResourceJob(Number(authStore.currentUserId), snapshot.taskId, this.currentSessionId)
      this.isGeneratingResources = true
      this.resourceGenerationRetryable = false
      try {
        const response = await this.waitForResourceGenerationJob(snapshot.taskId)
        await this.completeResourceGeneration(response)
        return true
      } finally {
        this.isGeneratingResources = false
      }
    },
    mergeGeneratedResources(newArtifacts = []) {
      if (!Array.isArray(newArtifacts) || !newArtifacts.length) return
      const byType = new Map()
      for (const item of this.generatedResources) {
        if (item?.artifactType) byType.set(item.artifactType, item)
      }
      for (const item of newArtifacts) {
        if (item?.artifactType) byType.set(item.artifactType, item)
      }
      this.generatedResources = Array.from(byType.values())
    },
    async appendTutoringArtifacts(artifacts = []) {
      if (!Array.isArray(artifacts) || !artifacts.length) return
      const merged = [...artifacts, ...this.generatedResources]
      const seen = new Set()
      this.generatedResources = merged.filter((item) => {
        const key = item.id || `${item.artifactType}-${item.runId}`
        if (seen.has(key)) return false
        seen.add(key)
        return true
      })
    },
    resetTransientState() {
      this.streamingText = ''
      this.uploadedAssessment = null
      this.assessmentResult = null
      this.diagnosticReport = null
      this.weakNodes = []
      this.generatedResources = []
      this.agentSteps = []
      this.lastResourceRunId = null
      this.resourceReviewSummary = ''
      this.resourceGenerationProgress = 0
      this.resourceGenerationStatus = ''
      this.resourceGenerationEtaSeconds = 0
      this.resourceGenerationRetryable = false
      this.currentResourceTaskId = null
    },
    async clearUserState(userId) {
      if (this.currentResourceTaskId && this.isGeneratingResources) {
        await cancelResourceGenerationJob(this.currentResourceTaskId).catch(() => null)
      }
      removeResourceJob(userId)
      if (userId) {
        try {
          localStorage.removeItem(`${ACTIVE_SESSION_KEY}_${userId}`)
        } catch {
          // ignore storage restrictions
        }
      }
      this.$reset()
    },
  },
})

function buildWeakNodesFromAssessment(data) {
  const errorPattern = sanitizeWeakPointLabel(data?.errorPattern || data?.error_pattern)
  if (!errorPattern) return []
  const confidence = Number(data?.confidence ?? 0.55)
  const mastery = Math.max(5, Math.min(95, Math.round((1 - Math.min(1, Math.max(0, confidence))) * 100)))
  return [
    {
      nodeName: errorPattern,
      knowledgeLayer: inferKnowledgeLayer(errorPattern),
      masteryScore: mastery,
    },
  ]
}

function sanitizeWeakPointLabel(value) {
  const text = String(value || '').trim()
  if (!text || text === 'unspecified' || text === '待观察' || text.length > 60) return ''
  if (/[。！？!?]/.test(text)) return ''
  if (/(建议|推荐|请先|请完成|可以通过|下一步|继续学习|上传|重试|点击|生成资源)/.test(text)) return ''
  return text
}

function inferKnowledgeLayer(text = '') {
  if (/math|matrix|formula|derivative|链式|矩阵|公式|推导/i.test(text)) return 'MATH'
  if (/algorithm|backprop|gradient|code|python|pytorch|反向|算法|代码/i.test(text)) return 'ALGORITHM'
  return 'APPLICATION'
}

function layerLabel(layer = '') {
  return {
    APPLICATION: '应用层',
    ALGORITHM: '算法层',
    MATH: '数学层',
  }[layer] || layer || '作业评测'
}

function toResourceCard(resource) {
  const numericId = resource?.id != null && !Number.isNaN(Number(resource.id))
    ? Number(resource.id)
    : null
  const historicalVideoScript = resource.artifactType === 'VIDEO_SCRIPT'
  const videoFields = historicalVideoScript ? resolveVideoScriptDisplay(resource) : null
  const rawSummary = videoFields?.summary
    || firstLine(stripAgentPreamble(resource.contentMarkdown || resource.reviewNotes || ''))
  const provenance = parseJsonObject(resource.contentJson)
  const origin = provenance.origin
    || (provenance.degraded ? 'DEGRADED' : '')
  const degraded = Boolean(provenance.degraded)
    || origin === 'DEGRADED'
    || resource.publishStatus === 'DEGRADED'
    || resource.publishStatus === 'BLOCKED'
  return {
    id: numericId,
    artifactType: historicalVideoScript ? 'VISUALIZATION' : resource.artifactType,
    originalArtifactType: historicalVideoScript ? 'VIDEO_SCRIPT' : resource.artifactType,
    title: resource.title || titleForType(resource.artifactType),
    type: typeLabel(resource.artifactType),
    status: statusLabel(resource.validationStatus, resource.publishStatus, degraded),
    summary: rawSummary,
    content: videoFields?.scriptContent || stripAgentPreamble(resource.contentMarkdown || ''),
    contentJson: resource.contentJson || '',
    generationMode: provenance.generation_mode || '',
    origin,
    degraded,
    fallbackReason: provenance.fallback_reason || '',
    generationAgent: provenance.agent || '',
    reviewNotes: resource.reviewNotes || '',
    validationStatus: resource.validationStatus || 'UNVERIFIED',
    publishStatus: resource.publishStatus || 'PUBLISHED',
    verificationAuditJson: resource.verificationAuditJson || '',
    progress: resource.progress,
    mediaTaskId: resource.mediaTaskId || '',
    mediaStatus: resource.mediaStatus || '',
    mediaUrl: resource.mediaUrl || '',
    coverImageUrl: resource.coverImageUrl || '',
    mediaError: resource.mediaError || '',
    narrationAudioUrl: videoFields?.narrationAudioUrl || '',
    citations: parseJsonArray(resource.citationsJson),
    runId: resource.runId || '',
    groundingMetrics: resource.groundingMetrics || null,
    isShowcase: Boolean(resource.showcase || resource.isShowcase || origin === 'DEMO'),
    sessionTopic: resource.sessionTopic || '',
    learningSessionId: resource.learningSessionId ?? null,
  }
}

function titleForType(type) {
  return {
    HANDOUT: '专业课程讲义',
    QUIZ: '分层练习题库',
    MINDMAP: '知识点思维导图',
    LEARNING_PATH: '个性化学习路径',
    CODE_PRACTICE: '代码实操案例',
    EXTENDED_READING: '拓展阅读材料',
    VIDEO_SCRIPT: '视频/动画脚本与分镜',
    VISUALIZATION: '可交互可视化',
  }[type] || '学习资源'
}

function typeLabel(type) {
  return {
    HANDOUT: '讲义',
    QUIZ: '题库',
    MINDMAP: '导图',
    LEARNING_PATH: '路径',
    CODE_PRACTICE: '实操',
    EXTENDED_READING: '阅读',
    VIDEO_SCRIPT: '脚本',
    VISUALIZATION: '可视化',
  }[type] || '资源'
}

function statusLabel(status, publishStatus, degraded) {
  if (publishStatus === 'BLOCKED') return '已拦截'
  if (degraded || publishStatus === 'DEGRADED') return '需修复 / 重试'
  return {
    GROUNDED: '知识库增强',
    NO_EVIDENCE: '模型直接生成',
    RAG_UNUSED: '模型直接生成',
    INVALID_CITATION: '需复核',
    AUTO_PUSH: '专项推送',
    PENDING: '待生成',
    UNVERIFIED: '未校验',
  }[status] || status || '未校验'
}

function firstLine(text) {
  return text
    .split('\n')
    .map((line) => line.replace(/^#+\s*/, '').replace(/^>\s*/, '').trim())
    .find(Boolean)
    ?.slice(0, 88) || '已生成资源，点击查看详情。'
}

function parseJsonArray(value) {
  if (!value) return []
  if (Array.isArray(value)) return value
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function parseJsonObject(value) {
  if (!value) return {}
  if (typeof value === 'object' && !Array.isArray(value)) return value
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

export function mapResourceToCard(resource, meta = {}) {
  return { ...toResourceCard(resource), ...meta }
}
