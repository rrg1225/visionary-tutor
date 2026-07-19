<template>
  <div class="vt-workbench">
    <aside class="vt-sidebar vt-glass">
      <div class="sidebar-profile vt-card" data-testid="workbench-ready">
        <div class="vt-eyebrow">当前用户</div>
        <div class="profile-name">{{ authStore.displayName || '访客' }}</div>
        <RouterLink v-if="!authStore.isRegistered" to="/profile" class="vt-btn vt-btn-outline vt-btn-sm">
          登录 / 注册
        </RouterLink>
      </div>

      <LearningStateAssist
        context-type="AI_TUTOR"
        context-key="main-dialogue"
        context-title="AI 辅导主对话"
        @intervention="onConfusionIntervention"
      />

    </aside>

    <main class="vt-main-canvas">
      <LearningProgressStepper
        :is-registered="authStore.isRegistered"
        :onboarding-complete="userProfile.onboardingComplete"
        :has-resources="resourceCards.length > 0"
        :is-generating-resources="learningSession.isGeneratingResources"
        @generate-resources="openResourceCustomizer"
        @open-report-panel="openReportPanel"
      />

      <div v-if="hasWorkbenchNotices" class="workbench-notices" aria-live="polite">
        <div
          v-if="userProfile.profileSyncNotice"
          class="workbench-notice workbench-notice--sync"
          data-testid="profile-sync-banner"
        >
          <p>{{ userProfile.profileSyncNotice }}</p>
          <button type="button" class="notice-dismiss" @click="userProfile.clearProfileSyncNotice()">知道了</button>
        </div>

        <div
          v-if="remediationProgress"
          class="workbench-notice workbench-notice--progress"
          data-testid="remediation-progress-banner"
        >
          <p>
            <strong>专项补救生成中</strong>
            · {{ remediationProgress.message }}
            <span v-if="remediationProgress.percent">（{{ remediationProgress.percent }}%）</span>
            <span v-if="remediationProgress.agentName" class="notice-meta">{{ remediationProgress.agentName }}</span>
          </p>
        </div>

        <div
          v-if="policyBanner"
          class="workbench-notice workbench-notice--policy"
          data-testid="policy-banner"
        >
          <p><strong>{{ pendingPushId ? '为什么推荐：' : '学习建议：' }}</strong>{{ formatPolicyBanner(policyBanner) }}</p>
          <button type="button" class="notice-dismiss" @click="dismissPolicyBanner">知道了</button>
        </div>
      </div>

      <section class="chat-canvas vt-card">
        <div
          v-if="knowledgeBaseHaBannerVisible"
          class="kb-ha-banner"
          role="status"
          data-testid="kb-ha-banner"
        >
          已切换至本地高可用检索，继续保持课程知识证据支持。
        </div>
        <div
          v-else-if="knowledgeBaseBannerVisible"
          class="kb-disconnected-banner"
          role="alert"
          data-testid="kb-disconnected-banner"
        >
          课程知识库正在同步，本次回答仍会由辅导模型完整生成。
        </div>

        <header class="panel-header">
          <div>
            <span class="vt-eyebrow">智眸学伴 · 学习循环</span>
            <h2 class="vt-subtitle">{{ workbenchSubtitle }}</h2>
          </div>
          <div class="panel-actions">
            <span v-if="authStore.isGuest" class="guest-quota-badge" data-testid="guest-quota-badge">
              游客剩余 {{ authStore.guestRemainingTurns }}/{{ authStore.guestMaxTurns }} 次
            </span>
            <button
              v-if="chat.ttsSupported"
              type="button"
              class="vt-btn vt-btn-ghost"
              :class="{ 'voice-active': chat.voiceEnabled }"
              :title="chat.voiceEnabled ? '关闭语音朗读' : '开启语音朗读'"
              @click="chat.toggleVoice()"
            >
              {{ chat.voiceEnabled ? '🔊 朗读开' : '🔇 朗读关' }}
            </button>
            <button class="vt-btn vt-btn-outline" :disabled="learningSession.isGeneratingResources" @click="openResourceCustomizer" data-testid="btn-generate-resources">
              {{ learningSession.isGeneratingResources ? '生成中...' : '定制生成资源' }}
            </button>
            <button class="vt-btn vt-btn-ghost drawer-toggle-btn" @click="ui.toggleDrawer()">
              {{ ui.drawerCollapsed ? '打开工具' : '收起工具' }}
            </button>
          </div>
        </header>

        <ChatHistoryPanel @session-changed="onChatSessionChanged" />

        <div v-if="chat.historyLoading" class="chat-history-loading">正在恢复聊天记录…</div>

        <div ref="chatContainer" class="chat-messages">
          <div v-if="!chat.messages.length && !chat.isStreaming" class="welcome-panel">
            <h3>今天想解决哪个人工智能学习问题？</h3>
            <p>可以问机器学习、深度学习、自然语言处理、强化学习等方向；计算机视觉与多模态内容会提供更深入的图解、代码和测评。</p>
            <div v-if="!resourceCards.length" class="welcome-cta">
              <p v-if="authStore.isGuest">
                建议先
                <RouterLink class="prose-link" to="/auth?mode=register">注册账号</RouterLink>
                ，再通过对话建档生成个性化资源。
              </p>
              <p v-else-if="!userProfile.isProfileComplete">
                建议先
                <RouterLink class="prose-link" to="/onboarding">对话建档（约 3 分钟）</RouterLink>
                ，系统才能生成 8 类 Agent 资源与学习路径。
              </p>
              <p v-else>
                画像已就绪。点击「定制生成资源」前往
                <RouterLink class="prose-link" to="/resources">我的资源库</RouterLink>
                选择主题与类型；也可前往
                <RouterLink class="prose-link" to="/assessment-fill">知识测评</RouterLink>
                上传作业获取掌握度反馈。
              </p>
              <button
                v-if="authStore.isRegistered && userProfile.isProfileComplete"
                type="button"
                class="vt-btn vt-btn-primary vt-btn-sm welcome-generate-btn"
                :disabled="learningSession.isGeneratingResources"
                @click="openResourceCustomizer"
              >
                去定制生成资源
              </button>
            </div>
            <p v-else-if="ui.drawerCollapsed" class="drawer-hint">
              已生成 {{ resourceCards.length }} 项学习资源（含路径、题库、导图等）。
              <RouterLink class="prose-link" to="/resources">打开我的资源库</RouterLink>
              或
              <button type="button" class="link-btn" @click="ui.setActivePanel('resources')">查看侧边预览</button>
            </p>
            <div class="prompt-chips">
              <button type="button" @click="usePrompt('帮我用图解方式理解 CNN 中 padding 和 stride 对特征图尺寸的影响')">
                CNN 尺寸推导
              </button>
              <button type="button" @click="usePrompt('请给我一条从图像处理基础到目标检测项目的学习路径')">
                生成学习路径
              </button>
              <button type="button" @click="usePrompt('帮我写一个 PyTorch 卷积层 shape 调试案例')">
                代码实操案例
              </button>
              <button type="button" @click="usePrompt('请比较 Transformer、RNN 和 CNN 在序列建模中的差异，并给我一条 NLP 入门路径')">
                NLP 与大模型
              </button>
              <button type="button" @click="usePrompt('用一个直观例子解释强化学习中的状态、动作、奖励和策略')">
                强化学习
              </button>
            </div>
          </div>
          <div v-for="msg in chat.messages" :key="msg.id" class="message" :class="msg.role">
            <div class="message-content markdown-body" v-html="renderMarkdown(msg.content)"></div>
            <div v-if="msg.role === 'assistant' && msg.usedRag" class="answer-badges">
              <span>已检索知识库</span>
            </div>
            <details v-if="msg.role === 'assistant' && msg.citations?.length" class="message-sources">
              <summary>查看本轮依据（{{ msg.citations.length }}）</summary>
              <ul>
                <li v-for="citation in msg.citations" :key="citation.citationId">
                  <strong>{{ citation.source || citation.sourcePath || "知识库资料" }}</strong>
                  <span>{{ citation.excerpt }}</span>
                </li>
              </ul>
            </details>
          </div>
          <div v-if="chat.isStreaming && chat.streamStatusText" class="stream-status">{{ chat.streamStatusText }}</div>
          <div v-if="chat.isStreaming && chat.agentTrace" class="agent-turn-trace" aria-label="智能体执行进度">
            <span class="agent-intent">{{ agentIntentLabel(chat.agentTrace.intent) }}</span>
            <span
              v-for="step in chat.agentTrace.steps"
              :key="step.id"
              :class="{ active: step.id === chat.agentTrace.currentAction, done: step.status === 'COMPLETED' }"
            >
              {{ step.label }}
            </span>
          </div>
          <div v-if="chat.isStreaming && chat.currentStreamContent" class="message assistant streaming">
            <div class="message-content markdown-body">
              <span v-html="renderMarkdown(chat.currentStreamContent)"></span><span class="cursor">|</span>
            </div>
          </div>
        </div>

        <div v-if="showTutoringActions" class="tutoring-actions vt-card">
          <div class="tutoring-head">
            <strong>多模态辅导</strong>
            <span class="tutoring-sub">基于当前对话，生成导图或本地演示动画＋文字注解（结果在「我的资源库」）</span>
          </div>
          <div class="tutoring-buttons">
          <button
            type="button"
            class="vt-btn vt-btn-outline vt-btn-sm"
            :disabled="Boolean(tutoringBusy)"
            @click="generateTutoringAssets(['MINDMAP'])"
          >
            {{ tutoringBusy === 'MINDMAP' ? '生成中...' : '导图图解' }}
          </button>
          <button
            type="button"
            class="vt-btn vt-btn-outline vt-btn-sm"
            :disabled="Boolean(tutoringBusy)"
            @click="generateTutoringAssets(['VISUALIZATION'])"
          >
            {{ tutoringBusy === 'VISUALIZATION' ? '生成中...' : '动画讲解' }}
          </button>
          <button
            type="button"
            class="vt-btn vt-btn-primary vt-btn-sm"
            :disabled="Boolean(tutoringBusy)"
            @click="generateTutoringAssets(['MINDMAP', 'VISUALIZATION'])"
          >
            {{ tutoringBusy === 'ALL' ? '生成中...' : '导图＋动画' }}
          </button>
          </div>
          <p v-if="tutoringMessage" class="tutoring-hint">{{ tutoringMessage }}</p>
        </div>

        <div class="chat-input">
          <label class="tutoring-mode-select">
            <span>辅导方式</span>
            <select
              v-model="chat.tutoringMode"
              class="vt-input"
              :disabled="chat.isStreaming"
              @change="persistTutoringMode"
            >
              <option value="AUTO">自动</option>
              <option value="HINT">只给提示</option>
              <option value="STEP_BY_STEP">分步带我做</option>
              <option value="DIRECT_ANSWER">直接看答案</option>
            </select>
          </label>
          <input
            v-model="chat.inputMessage"
            class="vt-input"
            placeholder="输入你的问题，例如：帮我理解卷积反向传播"
            :disabled="chat.isStreaming"
            @keyup.enter="chat.sendMessage()"
          />
          <button class="vt-btn vt-btn-primary" :disabled="!chat.inputMessage?.trim() || chat.isStreaming" @click="chat.sendMessage()" data-testid="btn-send-chat">
            发送
          </button>
          <button v-if="chat.isStreaming" class="vt-btn vt-btn-ghost" @click="chat.stopStream()">停止</button>
        </div>
      </section>
    </main>

    <aside class="vt-action-drawer vt-glass" :class="{ collapsed: ui.drawerCollapsed }">
      <div class="drawer-header">
        <div>
          <h3 class="vt-subtitle">学习闭环产出</h3>
          <p class="drawer-subtitle vt-text-muted">学情画像 · 知识诊断 · 资源干预 · 测评闭环</p>
        </div>
        <button class="vt-btn vt-btn-ghost drawer-toggle-btn" @click="ui.toggleDrawer()">{{ ui.drawerCollapsed ? '展开' : '收起' }}</button>
      </div>

      <div class="drawer-tabs" role="tablist" aria-label="Agent 产出区面板">
        <button
          v-for="panel in ui.panels"
          :key="panel.id"
          type="button"
          class="drawer-tab"
          :class="{ active: ui.activePanel === panel.id }"
          @click="ui.setActivePanel(panel.id)"
        >
          {{ panel.title }}
        </button>
      </div>

      <div class="drawer-body">
      <div v-if="ui.confusionOfferActive" class="drawer-status-offer">
        <button
          type="button"
          class="confusion-offer-btn"
          :disabled="ui.confusionOfferLoading"
          @click="ui.acceptConfusionOffer()"
        >
          <span class="confusion-offer-dot" aria-hidden="true"></span>
          <span class="confusion-offer-text">需要换一种讲法吗？</span>
        </button>
        <button type="button" class="confusion-offer-dismiss vt-btn vt-btn-ghost vt-btn-sm" @click="ui.dismissConfusionOffer()">稍后</button>
      </div>

      <ResourceDrawerPreview
        v-if="ui.activePanel === 'resources'"
        class="drawer-panel"
        :resources="resourceCards"
        :is-generating="learningSession.isGeneratingResources"
        :generation-progress="learningSession.resourceGenerationProgress"
        :generation-status="learningSession.resourceGenerationStatus"
      />

      <LearningProfilePanel
        v-if="ui.activePanel === 'profile'"
        class="drawer-panel"
        :dimensions="userProfile.profileDimensions"
        :updated-at="userProfile.updatedAt"
        :extraction-status="userProfile.extractionStatus"
        :extraction-message="userProfile.extractionMessage"
        :changed-dimensions="userProfile.lastChangedDimensions"
        :profile-version="userProfile.profileVersion"
        :path-version="userProfile.pathVersion"
        :profile-source="userProfile.profileSource"
      />

      <AssessmentResultPanel
        v-if="ui.activePanel === 'assessment'"
        class="drawer-panel"
        :response="assessmentResult"
      />

      <template v-if="ui.activePanel === 'diagnosis'">
        <DiagnosisReport
          v-if="diagnosisNodes.length"
          class="drawer-panel"
          :nodes="diagnosisNodes"
          :analysis="diagnosisAnalysis"
          :evidence="diagnosisEvidence"
        />
        <EmptyStateGuide
          v-else
          class="drawer-panel"
          compact
          icon="diagnosis"
          title="暂无知识诊断结果"
          description="在对话中描述具体难点（如放样融合、卷积尺寸推导、代码报错），或完成知识测评上传作业，薄弱节点会显示在此。"
          cta-text="前往知识测评"
          cta-to="/assessment-fill"
        />
        <GeneratedHandout v-if="handoutContent" class="drawer-panel" :content="handoutContent" />
      </template>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/authStore'
import { useUserProfileStore } from '../stores/userProfile'
import { useLearningSessionStore } from '../stores/learningSession'
import { useChatStream } from '../composables/useChatStream'
import { useWorkbenchUI } from '../composables/useWorkbenchUI'
import { generateTutoringMultimodal } from '../api/tutoring'
import { fetchLearnerState } from '../api/learner'
import { useNotificationStore } from '../stores/notification'
import { fetchHealth } from '../api/health'
import { streamRemediationProgress } from '../api/learningOs'
import LearningStateAssist from '../components/LearningStateAssist.vue'
const DiagnosisReport = defineAsyncComponent(() => import('../components/DiagnosticReport.vue'))
import GeneratedHandout from '../components/GeneratedHandout.vue'
import LearningProfilePanel from '../components/LearningProfilePanel.vue'
import ResourceDrawerPreview from '../components/ResourceDrawerPreview.vue'
import AssessmentResultPanel from '../components/AssessmentResultPanel.vue'
import EmptyStateGuide from '../components/EmptyStateGuide.vue'
import LearningProgressStepper from '../components/LearningProgressStepper.vue'
import ChatHistoryPanel from '../components/ChatHistoryPanel.vue'
import { sanitizeAssistantContent } from '../utils/sanitizeAssistantContent'
import { serializeProfileSnapshot } from '../utils/profileSnapshot'
import { toastWarning } from '../utils/toast'

const authStore = useAuthStore()
const userProfile = useUserProfileStore()
const learningSession = useLearningSessionStore()
const notificationStore = useNotificationStore()
const chat = useChatStream()
const ui = useWorkbenchUI()
const route = useRoute()
const router = useRouter()
const tutoringBusy = ref('')
const tutoringMessage = ref('')
const policyBanner = ref('')
const pendingPushId = ref(null)
const remediationProgress = ref(null)
const markdownRenderer = shallowRef((source) => `<p>${String(source || '')
  .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  .replaceAll('\n', '<br>')}</p>`)
const knowledgeBaseDisconnected = ref(false)
const knowledgeBaseHaMode = ref(false)
let remediationAbort = null

const knowledgeBaseHaBannerVisible = computed(() => (
  knowledgeBaseHaMode.value || chat.knowledgeBaseHaMode
))

const knowledgeBaseBannerVisible = computed(() => (
  !knowledgeBaseHaBannerVisible.value
  && (knowledgeBaseDisconnected.value || chat.knowledgeBaseDisconnected)
))

const showTutoringActions = computed(() => (
  chat.messages.some((message) => message.role === 'user') && !chat.isStreaming
))

const diagnosisNodes = computed(() => learningSession.weakNodes)
const handoutContent = computed(() => learningSession.streamingText)
const assessmentResult = computed(() => learningSession.assessmentResult)
const resourceCards = computed(() => learningSession.resourceCards)
const workbenchSubtitle = '左侧对话 · 右侧学情与诊断 · 资源在「我的资源库」'

const hasWorkbenchNotices = computed(() => (
  Boolean(userProfile.profileSyncNotice)
  || Boolean(remediationProgress.value)
  || Boolean(policyBanner.value)
))

function formatPolicyBanner(message) {
  if (!message) return ''
  return String(message)
    .replace(/触发 Agent 协同生成补救资源/g, '正在为你生成补救资料')
    .replace(/Agent 协同/g, '')
    .replace(/\s{2,}/g, ' ')
    .trim()
}
const diagnosisAnalysis = computed(() => {
  const data = assessmentResult.value?.data ?? {}
  return learningSession.diagnosticReport?.reasoningTrace
    || data.correctiveFeedback
    || data.corrective_feedback
    || ''
})
const diagnosisEvidence = computed(() => {
  const data = assessmentResult.value?.data ?? {}
  return {
    caption: data.ocrText || data.ocr_text || learningSession.uploadedAssessment?.name || '',
    traceNote: data.errorPattern || data.error_pattern || '',
    buggyCode: data.ocrText || data.ocr_text || '',
  }
})

async function onConfusionIntervention(result) {
  if (result?.userInitiated) {
    ui.setResonanceState('intervening')
    userProfile.emotionState = '困惑'
    userProfile.attentionState = '认知负荷偏高'
    userProfile.persistProfile()
    if (chat.isStreaming) {
      chat.stopStream()
      await new Promise((resolve) => window.setTimeout(resolve, 120))
    }
    if (!chat.isStreaming) {
      chat.inputMessage = '我刚才对上一段解释有点困惑。请立刻换一种更简单的讲法：先用生活化比喻，再给一个最小例子，最后给我一道自检题。'
      await chat.sendMessage()
    }
    window.setTimeout(() => ui.setResonanceState(ui.cameraAssistEnabled ? 'listening' : 'idle'), 4000)
  }
}

function renderMarkdown(content) {
  return markdownRenderer.value(sanitizeAssistantContent(content || ''))
}

function persistTutoringMode() {
  localStorage.setItem('vt_tutoring_mode', chat.tutoringMode)
}

function usePrompt(prompt) {
  chat.inputMessage = prompt
}

function lastUserQuestion() {
  const userMessages = chat.messages.filter((message) => message.role === 'user')
  return userMessages[userMessages.length - 1]?.content || chat.inputMessage || '当前辅导问题'
}

function agentIntentLabel(intent) {
  const labels = {
    TUTORING: '概念辅导',
    CODE_TUTORING: '代码辅导',
    ASSESSMENT_SUPPORT: '题目与作业分析',
    READING_GUIDANCE: '阅读辅导',
    LEARNING_PLANNING: '学习规划',
    PLATFORM_GUIDANCE: '平台使用指导',
  }
  return labels[intent] || '学习智能体'
}


function openResourceCustomizer() {
  if (!authStore.isRegistered) {
    toastWarning('请先注册账号，以生成并保存学习资源', 4000)
    router.push({ path: '/auth', query: { mode: 'register', redirect: '/resources' } })
    return
  }
  router.push('/resources')
}

function syncDrawerVisibility() {
  ui.syncDrawerFromContext({
    hasResources: learningSession.resourceCards.length > 0,
    profileReady: userProfile.isProfileComplete,
    generating: learningSession.isGeneratingResources,
  })
}

function applyRoutePanelQuery() {
  const panel = route.query.panel
  if (panel === 'resources') {
    router.replace('/resources')
    return
  }
  if (typeof panel === 'string' && ui.panels.some((item) => item.id === panel)) {
    ui.setActivePanel(panel)
  }
}

function applyRoutePromptQuery() {
  const prompt = typeof route.query.prompt === 'string' ? route.query.prompt.trim() : ''
  if (prompt && !chat.inputMessage?.trim()) {
    chat.inputMessage = prompt
  }
}

function onAuthContinueAction(event) {
  const action = event.detail
  if (action?.type === 'generateResources') {
    openResourceCustomizer()
  }
}

function consumeStoredPendingAction() {
  try {
    const raw = sessionStorage.getItem('vt_pending_action')
    if (!raw) return
    sessionStorage.removeItem('vt_pending_action')
    onAuthContinueAction({ detail: JSON.parse(raw) })
  } catch {
    sessionStorage.removeItem('vt_pending_action')
  }
}

async function syncLearnerOsState() {
  if (!authStore.isRegistered || !authStore.currentUserId) return
  try {
    const state = await fetchLearnerState({ silent: true })
    userProfile.applyLearnerOsState(state)
    const push = state.pendingRecommendationPush
    if (push?.message) {
      policyBanner.value = push.message
      pendingPushId.value = push.pushId || null
    } else if (state.lastPolicyReason) {
      policyBanner.value = state.lastPolicyReason
    }
  } catch (error) {
    console.warn('[HomeView] learner OS sync skipped:', error?.message || error)
  }
}

async function dismissPolicyBanner() {
  policyBanner.value = ''
  pendingPushId.value = null
  await notificationStore.dismissRecommendation()
}

async function trackRemediationProgress(runId) {
  if (!runId) return
  remediationAbort?.abort()
  remediationAbort = new AbortController()
  remediationProgress.value = { message: '任务已加入队列', percent: 5, agentName: 'LearningOS' }

  try {
    const finalProgress = await streamRemediationProgress({
      runId,
      signal: remediationAbort.signal,
      onProgress: (progress) => {
        remediationProgress.value = progress
      },
    })
    if (finalProgress?.status === 'COMPLETE') {
      policyBanner.value = finalProgress.message || '专项补救资源已生成'
      await learningSession.hydrateGeneratedResources()
      router.push('/resources')
    } else if (finalProgress?.status === 'FAILED') {
      policyBanner.value = finalProgress.message || '专项补救生成失败'
    }
  } catch (error) {
    if (error?.name !== 'AbortError') {
      console.warn('[HomeView] remediation progress stream failed:', error?.message || error)
    }
  } finally {
    remediationProgress.value = null
    remediationAbort = null
  }
}

function buildDialogueContext() {
  return chat.messages
    .slice(-4)
    .map((message) => `${message.role === 'user' ? '学生' : '助教'}：${message.content}`)
    .join('\n')
}

async function generateTutoringAssets(modes = null) {
  if (!authStore.isRegistered) {
    authStore.openAuthModal('register')
    return
  }

  const session = await learningSession.ensureCurrentSession(lastUserQuestion())
  if (!session?.id) return

  tutoringBusy.value = modes?.length === 1 ? modes[0] : 'ALL'
  tutoringMessage.value = '正在根据辅导上下文生成导图或本地演示动画…'

  try {
    const result = await generateTutoringMultimodal({
      learningSessionId: session.id,
      question: lastUserQuestion(),
      topic: lastUserQuestion().slice(0, 80) || session.topic,
      dialogueContext: buildDialogueContext(),
      learnerProfileSnapshot: serializeProfileSnapshot(
        userProfile.profileSnapshot,
        userProfile.profileDimensions,
        userProfile.aiTeacherPreferences,
      ),
      modes,
    })
    await learningSession.appendTutoringArtifacts(result.artifacts || [])
    tutoringMessage.value = result.message || '辅导图解已加入资源库'
    router.push('/resources')
  } catch (error) {
    tutoringMessage.value = error?.message || '辅导图解生成失败'
  } finally {
    tutoringBusy.value = ''
  }
}

function openReportPanel() {
  if (diagnosisNodes.value.length) {
    ui.setActivePanel('diagnosis')
    return
  }
  router.push('/resources')
}

async function runPostOnboardingGeneration() {
  openResourceCustomizer()
  const topic = userProfile.goal || '基于建档画像的个性化学习'
  await learningSession.ensureCurrentSession(topic)
}

async function syncKnowledgeBaseHealth() {
  try {
    await fetchHealth()
    // Public health intentionally exposes no dependency topology. The per-request
    // rag_context SSE event is authoritative for Chroma/BM25/degraded UI state.
    knowledgeBaseHaMode.value = false
    knowledgeBaseDisconnected.value = false
  } catch {
    knowledgeBaseDisconnected.value = true
    knowledgeBaseHaMode.value = false
  }
}

async function hydrateChatHistory() {
  if (authStore.isRegistered && learningSession.currentSessionId) {
    await chat.hydrateFromSession(learningSession.currentSessionId)
    return
  }
  chat.loadGuestMessages()
}

async function onChatSessionChanged(sessionId) {
  if (authStore.isRegistered && sessionId) {
    await chat.hydrateFromSession(sessionId)
  } else {
    chat.clearMessages()
  }
}

onMounted(async () => {
  void import('../utils/simpleMarkdown').then((module) => { markdownRenderer.value = module.renderSimpleMarkdown })
  const suggestedTutorPrompt = sessionStorage.getItem('vt_suggested_tutor_prompt')
  if (suggestedTutorPrompt) {
    sessionStorage.removeItem('vt_suggested_tutor_prompt')
    chat.inputMessage = suggestedTutorPrompt
  }
  userProfile.hydrateFromStorage()
  userProfile.syncAccountFromAuth()
  window.addEventListener('auth:continueAction', onAuthContinueAction)
  void syncKnowledgeBaseHealth()
  try {
    await learningSession.ensureCurrentSession(userProfile.goal || 'General tutoring')
    await learningSession.hydrateGeneratedResources()
    await hydrateChatHistory()
    await syncLearnerOsState()
    if (userProfile.consumePostOnboardingGeneration()) {
      await runPostOnboardingGeneration()
    }
  } catch (error) {
    console.warn('[HomeView] session hydrate skipped:', error?.message || error)
  }
  syncDrawerVisibility()
  if (userProfile.onboardingComplete || resourceCards.value.length > 0) {
    ui.expandDrawer('resources')
  }
  applyRoutePanelQuery()
  applyRoutePromptQuery()
  consumeStoredPendingAction()
  if (import.meta.env.DEV || import.meta.env.VITE_E2E_HOOKS === 'true') {
    window.__visionaryE2e = { trackRemediation: trackRemediationProgress }
  }
})

watch(
  () => [userProfile.onboardingComplete, resourceCards.value.length],
  ([onboardingComplete, resourceCount]) => {
    if (onboardingComplete || resourceCount > 0) {
      ui.expandDrawer('resources')
    }
  },
)

onUnmounted(() => {
  window.removeEventListener('auth:continueAction', onAuthContinueAction)
})

watch(
  () => [learningSession.resourceCards.length, userProfile.onboardingComplete, learningSession.isGeneratingResources],
  () => syncDrawerVisibility(),
)

watch(
  () => route.query.panel,
  () => applyRoutePanelQuery(),
)

watch(
  () => route.query.prompt,
  () => applyRoutePromptQuery(),
)

watch(
  () => notificationStore.pendingRecommendation,
  (push) => {
    if (push?.message) {
      policyBanner.value = push.message
      pendingPushId.value = push.pushId || null
    }
  },
  { deep: true }
)
</script>

<style scoped>
@media (min-width: 1280px) {
  .vt-workbench {
    display: grid;
    grid-template-columns: 220px minmax(560px, 1.7fr) minmax(300px, 0.8fr);
    align-items: start;
  }

  .vt-sidebar {
    width: auto;
  }

  .vt-action-drawer {
    width: auto;
  }

  .vt-action-drawer.collapsed {
    width: auto;
    padding: var(--vt-space-5);
    opacity: 1;
    pointer-events: auto;
    height: auto;
  }

  .drawer-toggle-btn {
    display: none;
  }
}

@media (max-width: 760px) {
  .vt-main-canvas {
    order: 1;
  }

  .vt-sidebar {
    order: 2;
  }

  .vt-action-drawer {
    order: 3;
  }

  .chat-input {
    position: sticky;
    bottom: 0;
    z-index: 3;
    background: var(--vt-surface);
  }
}

.chat-canvas {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 520px;
}

.kb-ha-banner {
  margin: var(--vt-space-3) var(--vt-space-4) 0;
  padding: var(--vt-space-3) var(--vt-space-4);
  border-radius: var(--vt-radius-md);
  background: rgba(14, 116, 144, 0.08);
  border: 1px solid rgba(14, 116, 144, 0.28);
  color: #0e7490;
  font-size: var(--vt-text-sm);
}

.kb-disconnected-banner {
  margin: var(--vt-space-3) var(--vt-space-4) 0;
  padding: var(--vt-space-3) var(--vt-space-4);
  border-radius: var(--vt-radius-md);
  background: rgba(220, 38, 38, 0.08);
  border: 1px solid rgba(220, 38, 38, 0.25);
  color: #b91c1c;
  font-size: var(--vt-text-sm);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
  padding: var(--vt-space-4);
  border-bottom: 1px solid var(--vt-border-light);
}

.panel-actions {
  display: flex;
  align-items: center;
  gap: var(--vt-space-2);
  flex-wrap: wrap;
}

.guest-quota-badge {
  padding: var(--vt-space-1) var(--vt-space-3);
  border: 1px solid rgba(13, 148, 136, 0.28);
  border-radius: var(--vt-radius-full);
  background: rgba(13, 148, 136, 0.08);
  color: var(--vt-accent-teal-dark);
  font-size: var(--vt-text-xs);
  font-weight: 700;
  white-space: nowrap;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: var(--vt-space-4);
}

.chat-history-loading {
  padding: 0.35rem 1rem;
  font-size: 0.82rem;
  color: #64748b;
  background: rgba(248, 250, 252, 0.9);
  border-bottom: 1px solid var(--vt-border-light);
}

.chat-input {
  display: flex;
  gap: var(--vt-space-2);
  padding: var(--vt-space-4);
  border-top: 1px solid var(--vt-border-light);
}

.tutoring-mode-select {
  display: grid;
  gap: 3px;
  flex: 0 0 132px;
  color: var(--vt-text-tertiary);
  font-size: 11px;
}

.tutoring-mode-select .vt-input {
  min-height: 40px;
  padding-block: 6px;
}

.tutoring-actions {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-3);
  margin: 0 var(--vt-space-4) var(--vt-space-3);
  padding: var(--vt-space-3) var(--vt-space-4);
}

.tutoring-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tutoring-head strong {
  font-size: var(--vt-text-sm);
  color: var(--vt-text-primary);
}

.tutoring-sub {
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
  line-height: 1.5;
}

.tutoring-buttons {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--vt-space-2);
}

.tutoring-label {
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
}

.tutoring-hint {
  width: 100%;
  margin: 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
}

.message {
  margin-bottom: var(--vt-space-3);
}

.message.user {
  display: flex;
  justify-content: flex-end;
}

.message.assistant {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--vt-space-1);
}

.message .answer-badges {
  display: flex;
  gap: var(--vt-space-2);
}

.message .answer-badges span {
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(13, 148, 136, 0.1);
  color: var(--vt-accent-teal-dark);
  font-size: 11px;
}

.message .message-sources {
  max-width: min(760px, 86%);
  color: var(--vt-text-secondary);
  font-size: 12px;
}

.message .message-sources summary {
  cursor: pointer;
  color: var(--vt-accent-teal-dark);
  font-weight: 600;
}

.message .message-sources ul {
  margin: 0.35rem 0 0;
  padding-left: 1.1rem;
  display: grid;
  gap: 0.35rem;
}

.message .message-sources li strong {
  display: block;
  color: var(--vt-text-primary);
}

.message-content {
  max-width: min(760px, 86%);
  padding: var(--vt-space-3) var(--vt-space-4);
  border-radius: var(--vt-radius-lg);
  background: var(--vt-bg-secondary);
  color: var(--vt-text-primary);
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.message-content :deep(.markdown-table-wrap) {
  max-width: 100%;
  overflow-x: auto;
  margin: var(--vt-space-3) 0;
}

.message-content :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--vt-text-sm);
}

.message-content :deep(th),
.message-content :deep(td) {
  padding: var(--vt-space-2);
  border: 1px solid var(--vt-border-light);
  text-align: left;
  vertical-align: top;
}

.message-content :deep(th) {
  background: rgba(13, 148, 136, 0.08);
  color: var(--vt-text-primary);
  font-weight: 700;
}

.message-content :deep(hr) {
  border: 0;
  border-top: 1px solid var(--vt-border-light);
  margin: var(--vt-space-3) 0;
}

.message.user .message-content {
  background: var(--vt-accent-teal);
  color: var(--vt-text-inverse);
}

.stream-status {
  width: fit-content;
  margin-bottom: var(--vt-space-3);
  padding: var(--vt-space-2) var(--vt-space-3);
  border-radius: var(--vt-radius-full);
  background: rgba(13, 148, 136, 0.08);
  color: var(--vt-accent-teal-dark);
  font-size: var(--vt-text-xs);
}

.agent-turn-trace {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
  margin: 0 0 var(--vt-space-3);
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.agent-turn-trace span {
  padding: 4px 8px;
  border: 1px solid var(--vt-border-light);
  border-radius: 999px;
  background: var(--vt-surface);
}

.agent-turn-trace .agent-intent {
  border-color: rgba(13, 148, 136, 0.35);
  color: var(--vt-accent-teal-dark);
  font-weight: var(--vt-font-semibold);
}

.agent-turn-trace span.active {
  border-color: rgba(59, 130, 246, 0.45);
  background: rgba(59, 130, 246, 0.08);
  color: var(--vt-accent-primary);
}

.agent-turn-trace span.done {
  opacity: 0.78;
}

.grounding-warning {
  margin-bottom: var(--vt-space-3);
  padding: var(--vt-space-2) var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  border: 1px solid rgba(234, 88, 12, 0.35);
  background: rgba(234, 88, 12, 0.08);
  color: #9a3412;
  font-size: var(--vt-text-xs);
  line-height: 1.5;
}

.remediation-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-3);
}

.workbench-notices {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-2);
}

.workbench-notice {
  display: flex;
  align-items: flex-start;
  gap: var(--vt-space-3);
  padding: var(--vt-space-2) var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  font-size: var(--vt-text-xs);
  line-height: 1.5;
  border: 1px solid var(--vt-border-light);
}

.workbench-notice p {
  margin: 0;
  flex: 1;
  min-width: 0;
  color: var(--vt-text-secondary);
}

.workbench-notice--sync {
  background: rgba(59, 130, 246, 0.06);
  border-color: rgba(59, 130, 246, 0.2);
}

.workbench-notice--progress {
  background: rgba(13, 148, 136, 0.06);
  border-color: rgba(13, 148, 136, 0.2);
}

.workbench-notice--policy {
  background: rgba(245, 158, 11, 0.08);
  border-color: rgba(245, 158, 11, 0.25);
}

.notice-meta {
  display: inline-block;
  margin-left: var(--vt-space-2);
  color: var(--vt-accent-teal-dark);
}

.notice-dismiss {
  flex-shrink: 0;
  padding: 2px 8px;
  border: none;
  background: transparent;
  color: var(--vt-text-tertiary);
  font-size: 11px;
  font-weight: var(--vt-font-medium);
  cursor: pointer;
  white-space: nowrap;
}

.notice-dismiss:hover {
  color: var(--vt-accent-teal-dark);
}

.remediation-agent {
  font-size: var(--vt-text-xs);
  color: var(--vt-accent-teal-dark);
  white-space: nowrap;
}

.source-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
  margin-bottom: var(--vt-space-3);
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
}

.source-strip button {
  max-width: 220px;
  min-height: 28px;
  padding: 0 var(--vt-space-2);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-full);
  background: var(--vt-bg-primary);
  color: var(--vt-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: help;
}

.welcome-panel {
  display: grid;
  gap: var(--vt-space-3);
  max-width: 720px;
  margin: auto;
  padding: var(--vt-space-6);
  text-align: center;
}

.welcome-panel h3 {
  margin: 0;
  font-size: var(--vt-text-xl);
}

.welcome-panel p {
  margin: 0;
  color: var(--vt-text-secondary);
}

.welcome-cta {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-4);
  border-radius: var(--vt-radius-md);
  background: rgba(59, 130, 246, 0.06);
  border: 1px dashed rgba(59, 130, 246, 0.25);
}

.welcome-generate-btn {
  justify-self: center;
}

.drawer-hint {
  font-size: var(--vt-text-sm);
  color: var(--vt-accent-teal);
}

.link-btn {
  background: none;
  border: none;
  padding: 0;
  color: var(--vt-accent-teal);
  text-decoration: underline;
  cursor: pointer;
  font: inherit;
}

.prompt-chips {
  display: flex;
  justify-content: center;
  gap: var(--vt-space-2);
  flex-wrap: wrap;
}

.prompt-chips button {
  min-height: 36px;
  padding: 0 var(--vt-space-3);
  border-radius: var(--vt-radius-full);
  border: 1px solid var(--vt-border-light);
  background: var(--vt-surface);
  color: var(--vt-text-secondary);
  text-decoration: none;
  cursor: pointer;
}

.vt-sidebar {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-3);
  align-self: start;
}

.vt-sidebar :deep(.learning-state) {
  padding: 0 var(--vt-space-1);
}

.vt-sidebar :deep(.learning-state-panel) {
  margin-top: 0;
}

.sidebar-profile,
.privacy-box {
  padding: var(--vt-space-4);
}

.profile-name {
  font-weight: var(--vt-font-medium);
  margin-top: var(--vt-space-1);
}

.privacy-box {
  display: grid;
  gap: var(--vt-space-3);
}

.privacy-box strong {
  font-size: var(--vt-text-sm);
  color: var(--vt-text-primary);
}

.privacy-box p {
  margin: var(--vt-space-1) 0 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
  line-height: 1.5;
}

.confusion-sensor-host {
  overflow: hidden;
  border-radius: var(--vt-radius-md);
  min-height: 160px;
  border: 1px solid var(--vt-border-light);
  background: rgba(15, 23, 42, 0.03);
}

.confusion-sensor-host :deep(.face-capture) {
  min-height: 160px;
}

.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--vt-space-3);
}

.drawer-subtitle {
  margin: var(--vt-space-1) 0 0;
  font-size: var(--vt-text-xs);
}

.drawer-tabs {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--vt-space-1);
  margin-bottom: var(--vt-space-3);
}

.drawer-tab {
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  padding: var(--vt-space-2) var(--vt-space-1);
  background: var(--vt-bg-secondary);
  color: var(--vt-text-secondary);
  cursor: pointer;
  font-size: 11px;
  line-height: 1.3;
}

.drawer-tab.active {
  color: var(--vt-text-primary);
  border-color: var(--vt-accent-teal);
  background: rgba(13, 148, 136, 0.08);
}

.drawer-panel {
  margin-bottom: var(--vt-space-4);
}

.drawer-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 2px;
}

.drawer-status-offer {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-2);
  padding: var(--vt-space-3);
  margin-bottom: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: rgba(13, 148, 136, 0.06);
  border: 1px solid rgba(13, 148, 136, 0.15);
}

.confusion-offer-btn {
  display: flex;
  align-items: center;
  gap: var(--vt-space-2);
  width: 100%;
  padding: var(--vt-space-2) var(--vt-space-3);
  text-align: left;
  font-size: var(--vt-text-sm);
  color: var(--vt-text-primary);
  background: transparent;
  border: none;
  border-radius: var(--vt-radius-md);
  cursor: pointer;
}

.confusion-offer-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: var(--vt-accent-teal);
}
</style>
