<template>
  <section class="onboarding-view">
    <header class="onboarding-header vt-card">
      <div class="header-copy">
        <span class="vt-eyebrow">渐进式学习画像</span>
        <h1 class="vt-title">快速了解你，也可以稍后再说</h1>
        <p class="vt-text-muted">
          建档不是使用门槛。选择少量偏好可以让推荐更准确；跳过后，系统也会在对话、测评和练习中逐步学习。
        </p>
      </div>
      <button type="button" class="vt-btn vt-btn-ghost" @click="skipOnboarding">
        跳过，先去提问
      </button>
    </header>

    <div class="mode-tabs" role="tablist" aria-label="建档方式">
      <button type="button" :class="{ active: mode === 'quick' }" @click="mode = 'quick'">
        <strong>30 秒快速选择</strong>
        <span>适合第一次使用</span>
      </button>
      <button type="button" :class="{ active: mode === 'dialogue' }" @click="startDialogue">
        <strong>分步对话建档</strong>
        <span>更深入地了解目标与基础</span>
      </button>
    </div>

    <div v-if="mode === 'quick'" class="quick-layout">
      <section class="quick-panel vt-card">
        <div v-for="group in quickGroups" :key="group.key" class="quick-group">
          <div class="quick-group-title">
            <h2>{{ group.title }}</h2>
            <span>{{ group.hint }}</span>
          </div>
          <div class="quick-options">
            <button
              v-for="option in group.options"
              :key="option"
              type="button"
              :class="{ selected: isSelected(group.key, option) }"
              @click="toggleOption(group.key, option)"
            >
              {{ option }}
            </button>
          </div>
        </div>

        <div class="quick-actions">
          <span>以后可以在个人中心随时修改。</span>
          <button type="button" class="vt-btn vt-btn-primary" :disabled="!hasQuickSelection || savingQuick" @click="saveQuickProfile">
            {{ savingQuick ? '正在保存…' : '保存偏好并开始学习' }}
          </button>
        </div>
      </section>

      <LearningProfilePanel
        class="profile-panel"
        :dimensions="userProfile.profileDimensions"
        :updated-at="userProfile.updatedAt"
        :extraction-status="userProfile.extractionStatus"
        :extraction-message="userProfile.extractionMessage"
        :changed-dimensions="userProfile.lastChangedDimensions"
      />
    </div>

    <div v-else class="onboarding-grid">
      <section class="chat-panel vt-card" aria-label="建档对话">
        <div class="dialogue-progress progress-row">
          <span>建档进度 {{ Math.min(stepIndex, dialogueScript.length) }}/{{ dialogueScript.length }}</span>
          <div><i :style="{ width: `${progressPercent}%` }"></i></div>
        </div>

        <div ref="chatContainer" class="chat-log">
          <div v-for="msg in messages" :key="msg.id" class="chat-bubble" :class="msg.role">
            <strong>{{ msg.role === 'assistant' ? '智眸学伴' : '你' }}</strong>
            <p>{{ msg.content }}</p>
          </div>
        </div>

        <template v-if="!finished && currentStep">
          <div class="chip-row">
            <button
              v-for="chip in currentStep.chips"
              :key="chip"
              type="button"
              class="vt-btn vt-btn-outline vt-btn-sm"
              :disabled="finalizing || validatingAnswer"
              @click="sendAnswer(chip)"
            >
              {{ chip }}
            </button>
          </div>

          <p v-if="answerValidationError" class="answer-validation-error" role="alert">
            {{ answerValidationError }}
          </p>

          <form class="chat-input-row" @submit.prevent="sendAnswer(inputMessage)">
            <input v-model="inputMessage" class="vt-input" :placeholder="currentStep.placeholder" :disabled="finalizing || validatingAnswer" />
            <button type="submit" class="vt-btn vt-btn-primary" :disabled="!inputMessage.trim() || finalizing || validatingAnswer">
              {{ finalizing ? '后台整理画像中…' : (validatingAnswer ? '正在检查回答…' : '发送') }}
            </button>
          </form>
        </template>

        <div v-else class="finish-card">
          <span class="vt-eyebrow">分步建档完成</span>
          <h3>已记录你的目标、基础、偏好和节奏</h3>
          <p>画像正在后台整理，不会阻塞你开始学习；其余维度会根据后续行为逐步补全。</p>
          <div class="finish-buttons">
            <button type="button" class="vt-btn vt-btn-primary" @click="enterLearning">开始学习</button>
            <button type="button" class="vt-btn vt-btn-outline" @click="mode = 'quick'">返回快速设置</button>
          </div>
        </div>
      </section>

      <LearningProfilePanel
        class="profile-panel"
        :dimensions="userProfile.profileDimensions"
        :updated-at="userProfile.updatedAt"
        :extraction-status="userProfile.extractionStatus"
        :extraction-message="userProfile.extractionMessage"
        :changed-dimensions="userProfile.lastChangedDimensions"
      />
    </div>
  </section>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LearningProfilePanel from '../components/LearningProfilePanel.vue'
import { validateOnboardingAnswer } from '../api/profile'
import { useUserProfileStore } from '../stores/userProfile'
import { safeNavigate } from '../utils/safeNavigate'

const router = useRouter()
const route = useRoute()
const userProfile = useUserProfileStore()

const mode = ref('quick')
const savingQuick = ref(false)
const stepIndex = ref(0)
const inputMessage = ref('')
const messages = ref([])
const chatContainer = ref(null)
const finished = ref(false)
const finalizing = ref(false)
const validatingAnswer = ref(false)
const answerValidationError = ref('')

const quickSelections = reactive({
  domains: [],
  goals: [],
  styles: [],
  pace: [],
})

const quickGroups = [
  {
    key: 'domains',
    title: '你正在关注什么领域？',
    hint: '可以多选，也可以暂时不确定',
    options: ['人工智能综合', '计算机视觉', '机器学习', '深度学习', '自然语言处理', '大模型与 RAG', '算法与数据结构', 'Python 编程', '数学与统计', '还不确定'],
  },
  {
    key: 'goals',
    title: '你目前最想完成什么？',
    hint: '用于决定学习路径的优先级',
    options: ['理解课程概念', '完成作业', '准备考试', '解决代码问题', '完成课程项目', '系统入门', '查漏补缺', '还不确定'],
  },
  {
    key: 'styles',
    title: '你更喜欢怎样学习？',
    hint: '系统会据此选择解释和资源形式',
    options: ['先给结论', '分步骤讲解', '图解与动画', '公式推导', '代码实操', '生活化比喻', '多做练习', '还不确定'],
  },
  {
    key: 'pace',
    title: '你希望采用什么节奏？',
    hint: '单选',
    options: ['轻量节奏', '标准节奏', '强化节奏', '还不确定'],
  },
]

const dialogueScript = [
  {
    prompt: '先聊聊你最近在学什么，或者最想解决什么问题？不知道具体方向也没关系。',
    placeholder: '例如：我在准备机器学习考试，希望补齐基础概念…',
    chips: ['准备课程考试', '完成一份作业', '解决代码或项目问题', '系统学习一个新方向', '我还不确定，想先体验'],
  },
  {
    prompt: '你的基础大概处于什么阶段？',
    placeholder: '描述学过的课程、工具或目前卡住的位置…',
    chips: ['刚开始接触', '有 Python 基础', '学过线代和概率', '上过机器学习课程', '做过深度学习项目', '我不确定自己的水平'],
  },
  {
    prompt: '你更喜欢怎样的解释？最近有什么内容总是学不明白？',
    placeholder: '例如：希望先讲结论，再用图解和代码举例…',
    chips: ['先结论后步骤', '喜欢图解和动画', '喜欢公式推导', '喜欢代码实操', '希望多用生活化比喻', '暂时没有明确偏好'],
  },
  {
    prompt: '最后，你希望学习节奏怎样？',
    placeholder: '例如：每天 20 分钟，先补基础再练习…',
    chips: ['轻量节奏，先解决眼前问题', '标准节奏，跟随课程进度', '强化节奏，多做挑战题和项目', '暂时不设固定节奏'],
  },
]

const currentStep = computed(() => dialogueScript[stepIndex.value] || null)
const progressPercent = computed(() => Math.round((Math.min(stepIndex.value, dialogueScript.length) / dialogueScript.length) * 100))
const hasQuickSelection = computed(() => Object.values(quickSelections).some((values) => values.length > 0))

function isSelected(group, option) {
  return quickSelections[group].includes(option)
}

function toggleOption(group, option) {
  const values = quickSelections[group]
  const uncertain = '还不确定'
  const single = group === 'pace'
  if (values.includes(option)) {
    quickSelections[group] = values.filter((item) => item !== option)
    return
  }
  if (single || option === uncertain) {
    quickSelections[group] = [option]
    return
  }
  quickSelections[group] = values.filter((item) => item !== uncertain).concat(option)
}

function informative(values) {
  return values.filter((value) => value !== '还不确定')
}

function learningTarget() {
  const unlock = typeof route.query.unlock === 'string' ? route.query.unlock : ''
  return unlock.startsWith('/') ? unlock : '/learn'
}

async function saveQuickProfile() {
  if (!hasQuickSelection.value || savingQuick.value) return
  savingQuick.value = true
  const domains = informative(quickSelections.domains)
  const goals = informative(quickSelections.goals)
  const styles = informative(quickSelections.styles)
  const pace = informative(quickSelections.pace)

  userProfile.saveProfile({
    name: userProfile.name,
    email: userProfile.email,
    focus: domains.join('、') || userProfile.focus,
    style: styles.join('、') || userProfile.style,
    goal: goals.join('、') || userProfile.goal,
    knowledgeBase: userProfile.knowledgeBase,
    cognitiveStyle: styles.join('、') || userProfile.cognitiveStyle,
    weakPoints: userProfile.weakPoints,
    errorPatterns: userProfile.errorPatterns,
    learningPace: pace[0] || userProfile.learningPace,
    emotionState: userProfile.emotionState,
    attentionState: userProfile.attentionState,
  })
  userProfile.markOnboardingComplete()

  const summary = [
    `关注领域：${domains.join('、') || '暂不确定'}`,
    `当前目标：${goals.join('、') || '暂不确定'}`,
    `学习偏好：${styles.join('、') || '暂不确定'}`,
    `学习节奏：${pace[0] || '暂不确定'}`,
  ].join('\n')
  void userProfile.extractFromLearningContext({ conversationText: summary, extractPhase: 'ONBOARDING_FINAL' })
  await safeNavigate(router, learningTarget(), { forceReload: false })
  savingQuick.value = false
}

function appendMessage(role, content) {
  messages.value.push({ id: `${Date.now()}-${Math.random()}`, role, content })
  nextTick(() => {
    if (chatContainer.value) chatContainer.value.scrollTop = chatContainer.value.scrollHeight
  })
}

function startDialogue() {
  mode.value = 'dialogue'
  if (!messages.value.length) appendMessage('assistant', dialogueScript[0].prompt)
}

async function sendAnswer(raw) {
  const text = String(raw || '').trim()
  if (!text || finished.value || finalizing.value || validatingAnswer.value) return

  validatingAnswer.value = true
  answerValidationError.value = ''
  try {
    const result = await validateOnboardingAnswer({
      stepIndex: stepIndex.value,
      question: currentStep.value?.prompt || '',
      answer: text,
    })
    if (!result.valid) {
      answerValidationError.value = result.reason || '回答与本轮问题关联不足，请重新回答。'
      return
    }
  } catch (error) {
    answerValidationError.value = error?.response?.data?.message || '回答检查暂时不可用，请稍后重试。'
    return
  } finally {
    validatingAnswer.value = false
  }

  appendMessage('user', text)
  inputMessage.value = ''
  userProfile.recordOnboardingAnswer()
  stepIndex.value += 1

  if (stepIndex.value >= dialogueScript.length) {
    finalizing.value = true
    userProfile.markOnboardingComplete()
    appendMessage('assistant', '已记录完成。画像会在后台整理，你现在就可以开始学习。')
    finished.value = true
    const dialogue = messages.value.map((item) => `${item.role === 'user' ? '学生' : '助教'}：${item.content}`).join('\n')
    void userProfile.extractFromLearningContext({ conversationText: dialogue, extractPhase: 'ONBOARDING_FINAL' })
      .finally(() => { finalizing.value = false })
    return
  }

  appendMessage('assistant', currentStep.value.prompt)
}

async function skipOnboarding() {
  userProfile.deferOnboarding()
  await safeNavigate(router, learningTarget(), { forceReload: false })
}

async function enterLearning() {
  await safeNavigate(router, learningTarget(), { forceReload: false })
}

onMounted(() => {
  userProfile.hydrateFromStorage()
  userProfile.syncAccountFromAuth()
})
</script>

<style scoped>
.onboarding-view {
  display: grid;
  gap: var(--vt-space-5);
  width: min(1180px, 100%);
  margin: 0 auto;
}

.onboarding-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-5);
  padding: var(--vt-space-6);
}

.header-copy {
  max-width: 760px;
}

.header-copy p {
  line-height: 1.7;
}

.mode-tabs {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-3);
}

.mode-tabs button {
  display: grid;
  gap: var(--vt-space-1);
  padding: var(--vt-space-4);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-lg);
  background: var(--vt-surface);
  color: var(--vt-text-secondary);
  cursor: pointer;
  text-align: left;
}

.mode-tabs button.active {
  border-color: rgba(13, 148, 136, 0.6);
  background: rgba(13, 148, 136, 0.08);
  color: var(--vt-text-primary);
}

.mode-tabs span,
.quick-group-title span,
.quick-actions span {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.quick-layout,
.onboarding-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(300px, 0.65fr);
  gap: var(--vt-space-4);
  align-items: start;
}

.quick-panel,
.chat-panel {
  display: grid;
  gap: var(--vt-space-5);
  padding: var(--vt-space-5);
}

.quick-group {
  display: grid;
  gap: var(--vt-space-3);
}

.quick-group + .quick-group {
  padding-top: var(--vt-space-4);
  border-top: 1px solid var(--vt-border-light);
}

.quick-group-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--vt-space-3);
}

.quick-group h2 {
  margin: 0;
  color: var(--vt-text-primary);
  font-size: 1rem;
}

.quick-options,
.chip-row,
.finish-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}

.quick-options button {
  padding: 8px 13px;
  border: 1px solid var(--vt-border-light);
  border-radius: 999px;
  background: var(--vt-bg-primary);
  color: var(--vt-text-secondary);
  cursor: pointer;
  font: inherit;
  font-size: var(--vt-text-sm);
}

.quick-options button.selected {
  border-color: var(--vt-accent-teal);
  background: rgba(13, 148, 136, 0.12);
  color: var(--vt-accent-teal-dark);
  font-weight: var(--vt-font-semibold);
}

.quick-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
  padding-top: var(--vt-space-3);
}

.profile-panel {
  min-height: 520px;
}

.dialogue-progress {
  display: grid;
  gap: var(--vt-space-2);
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-xs);
}

.dialogue-progress > div {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--vt-border-light);
}

.dialogue-progress i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #3b82f6, #14b8a6);
  transition: width 180ms ease;
}

.chat-log {
  max-height: 430px;
  overflow: auto;
  display: grid;
  gap: var(--vt-space-3);
}

.chat-bubble {
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
}

.chat-bubble.user {
  background: rgba(59, 130, 246, 0.08);
}

.chat-bubble strong {
  display: block;
  margin-bottom: var(--vt-space-1);
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.chat-bubble p,
.finish-card p {
  margin: 0;
  color: var(--vt-text-secondary);
  line-height: 1.65;
}

.chat-input-row {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: var(--vt-space-2);
}

.answer-validation-error {
  margin: 0;
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: rgba(245, 158, 11, 0.1);
  color: #b45309;
  font-size: var(--vt-text-sm);
}

.finish-card {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-5);
  border: 1px solid rgba(13, 148, 136, 0.3);
  border-radius: var(--vt-radius-lg);
  background: rgba(13, 148, 136, 0.05);
}

.finish-card h3 {
  margin: 0;
}

@media (max-width: 960px) {
  .quick-layout,
  .onboarding-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .onboarding-header,
  .quick-actions,
  .quick-group-title {
    align-items: stretch;
    flex-direction: column;
  }

  .mode-tabs,
  .chat-input-row {
    grid-template-columns: 1fr;
  }
}
</style>
