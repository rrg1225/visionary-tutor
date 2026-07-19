<template>
  <section class="profile-fill-view vt-card vt-container">
    <header class="page-header">
      <span class="vt-eyebrow">账号设置</span>
      <h1 class="vt-title">账号与 AI 老师偏好</h1>
      <p class="vt-text-muted">
        学习画像由对话与作业评测自动构建；这里可以维护账号信息，也可以像对话助手一样调整 AI 老师的表达方式。如需更新画像，请回到
        <RouterLink class="prose-link" to="/onboarding">对话建档</RouterLink>
        或继续在学习循环中聊天。
      </p>
    </header>

    <section class="account-overview" aria-label="账户概览">
      <div><span>当前账户</span><strong>{{ authStore.displayName }}</strong></div>
      <div><span>学习数据</span><strong>已同步到账号</strong></div>
      <div><span>建档状态</span><strong>{{ userProfile.onboardingComplete ? '已完成' : '可稍后完善' }}</strong></div>
    </section>

    <form class="profile-form" @submit.prevent="submitProfile">
      <label class="vt-label">
        <span>显示名称</span>
        <input v-model="form.name" type="text" class="vt-input" name="name" autocomplete="name" />
      </label>

      <label class="vt-label">
        <span>邮箱</span>
        <input v-model="form.email" type="email" class="vt-input" name="email" autocomplete="email" />
      </label>

      <section class="preference-section wide" aria-labelledby="teacher-preference-title">
        <header>
          <span class="vt-eyebrow">AI 老师</span>
          <h2 id="teacher-preference-title" class="vt-subtitle">回答与语音风格</h2>
          <p class="vt-text-muted">偏好会随对话、题库答疑、拓展阅读和资源生成一起发送；不会改变知识与引用边界。</p>
        </header>
        <div class="preference-grid">
          <label class="vt-label">
            <span>说话语气</span>
            <select v-model="form.teacherTone" class="vt-input">
              <option>亲切自然</option><option>专业严谨</option><option>简洁直接</option><option>耐心鼓励</option><option>苏格拉底式引导</option>
            </select>
          </label>
          <label class="vt-label">
            <span>回答详细程度</span>
            <select v-model="form.responseDetail" class="vt-input">
              <option>自适应</option><option>精简</option><option>标准</option><option>详细</option>
            </select>
          </label>
          <label class="vt-label">
            <span>回答结构</span>
            <select v-model="form.answerStructure" class="vt-input">
              <option>先结论后步骤</option><option>循序渐进</option><option>例子优先</option><option>提问引导</option>
            </select>
          </label>
          <label class="vt-label">
            <span>鼓励程度</span>
            <select v-model="form.encouragementLevel" class="vt-input">
              <option>不需要</option><option>适度</option><option>多一些</option>
            </select>
          </label>
          <label class="vt-label">
            <span>表情符号</span>
            <select v-model="form.emojiUsage" class="vt-input">
              <option>不用</option><option>少量</option><option>适量</option>
            </select>
          </label>
          <label class="vt-label">
            <span>朗读音色倾向</span>
            <select v-model="form.teacherVoice" class="vt-input">
              <option>温和清晰</option><option>沉稳专业</option><option>活力明快</option>
            </select>
          </label>
          <label class="emotion-check wide">
            <input v-model="form.emotionSupportEnabled" type="checkbox" />
            <span>当系统识别到挫败或困惑时，先简短回应情绪，再继续讲解；不使用摄像头也能根据对话文字判断。</span>
          </label>
        </div>
      </section>

      <label class="vt-label wide">
        <span>专注领域（可修改）</span>
        <input
          v-model="form.focus"
          class="vt-input"
          name="focus"
          list="ai-focus-options"
          maxlength="120"
          placeholder="例如：人工智能综合、自然语言处理、强化学习"
        />
        <datalist id="ai-focus-options">
          <option value="人工智能（综合）"></option>
          <option value="计算机视觉与多模态"></option>
          <option value="机器学习与深度学习"></option>
          <option value="自然语言处理与大语言模型"></option>
          <option value="生成式人工智能"></option>
          <option value="强化学习与智能决策"></option>
          <option value="语音与音频智能"></option>
          <option value="机器人与具身智能"></option>
          <option value="算法、数据结构与数学基础"></option>
        </datalist>
        <span class="field-hint">可直接输入任意 AI 学习方向，计算机视觉仍会获得更丰富的专题内容。</span>
      </label>

      <p v-if="errorMessage" class="form-error wide" role="alert">{{ errorMessage }}</p>

      <div class="form-actions wide">
        <RouterLink class="vt-btn vt-btn-outline" to="/profile">返回档案</RouterLink>
        <button type="submit" class="vt-btn vt-btn-primary" :disabled="isSubmitting">
          {{ isSubmitting ? '保存中...' : '保存账号与 AI 老师偏好' }}
        </button>
      </div>
    </form>

    <section class="security-section" aria-labelledby="security-title">
      <div><span class="vt-eyebrow">安全设置</span><h2 id="security-title" class="vt-subtitle">隐私、记忆与账号安全</h2><p>摄像头默认关闭；可独立管理学习记忆、数据导出和隐私说明。</p></div>
      <div class="security-actions">
        <RouterLink class="vt-btn vt-btn-outline" to="/privacy">隐私中心</RouterLink>
        <RouterLink class="vt-btn vt-btn-outline" to="/memory">记忆管理</RouterLink>
        <RouterLink class="vt-btn vt-btn-ghost" to="/legal">用户协议</RouterLink>
      </div>
    </section>

    <section class="feedback-card" aria-labelledby="feedback-title">
      <header>
        <span class="vt-eyebrow">问题反馈</span>
        <h2 id="feedback-title" class="vt-subtitle">告诉我们哪里不顺手</h2>
        <p class="vt-text-muted">反馈会附带当前页面路径，便于定位；请勿填写密码、验证码等敏感信息。</p>
      </header>
      <form class="feedback-form" @submit.prevent="submitFeedback">
        <label class="vt-label">
          <span>反馈类型</span>
          <select v-model="feedback.category" class="vt-input" required>
            <option value="BUG">功能异常</option>
            <option value="UX">使用体验</option>
            <option value="CONTENT">知识或答案问题</option>
            <option value="SUGGESTION">功能建议</option>
          </select>
        </label>
        <label class="vt-label">
          <span>联系方式（可选）</span>
          <input v-model="feedback.contact" class="vt-input" maxlength="128" placeholder="邮箱或其他联系方式" />
        </label>
        <label class="vt-label wide">
          <span>具体情况</span>
          <textarea
            v-model="feedback.message"
            class="vt-input feedback-textarea"
            minlength="5"
            maxlength="2000"
            required
            placeholder="请描述你做了什么、看到了什么，以及你希望发生什么…"
          ></textarea>
        </label>
        <button type="submit" class="vt-btn vt-btn-outline wide" :disabled="feedbackSubmitting || feedback.message.trim().length < 5">
          {{ feedbackSubmitting ? '提交中…' : '提交反馈' }}
        </button>
      </form>
    </section>
  </section>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { fetchCurrentUser, updateCurrentUser } from '../api/users'
import { submitUserFeedback } from '../api/feedback'
import { useAuthStore } from '../stores/authStore'
import { useUserProfileStore } from '../stores/userProfile'
import { toastError, toastSuccess } from '../utils/toast'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const userProfile = useUserProfileStore()
const errorMessage = ref('')
const isSubmitting = ref(false)
const feedbackSubmitting = ref(false)

const form = reactive({
  name: '',
  email: '',
  focus: '人工智能（综合）',
  teacherTone: '亲切自然',
  responseDetail: '自适应',
  answerStructure: '先结论后步骤',
  encouragementLevel: '适度',
  emojiUsage: '少量',
  teacherVoice: '温和清晰',
  emotionSupportEnabled: true,
})

const feedback = reactive({
  category: 'UX',
  message: '',
  contact: '',
})

function applyFormFromLocal() {
  form.name = userProfile.name
  form.email = userProfile.email
  form.focus = userProfile.focus
  form.teacherTone = userProfile.teacherTone
  form.responseDetail = userProfile.responseDetail
  form.answerStructure = userProfile.answerStructure
  form.encouragementLevel = userProfile.encouragementLevel
  form.emojiUsage = userProfile.emojiUsage
  form.teacherVoice = userProfile.teacherVoice
  form.emotionSupportEnabled = userProfile.emotionSupportEnabled
}

onMounted(async () => {
  userProfile.hydrateFromStorage()
  userProfile.syncAccountFromAuth()
  applyFormFromLocal()

  if (!authStore.isRegistered) {
    return
  }

  try {
    const data = await fetchCurrentUser()
    if (data?.displayName) form.name = data.displayName
    if (data?.email) form.email = data.email
    if (data?.learningGoal) form.focus = data.learningGoal
    userProfile.saveAccount({ ...form })
    userProfile.saveAiTeacherPreferences({ ...form })
  } catch (error) {
    console.warn('[ProfileFill] 云端资料读取失败，已使用本地缓存:', error?.message || error)
  }
})

async function submitProfile() {
  if (isSubmitting.value) return
  errorMessage.value = ''
  isSubmitting.value = true

  try {
    userProfile.saveAccount({ ...form })
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/profile'

    if (authStore.isRegistered) {
      try {
        await updateCurrentUser({
          displayName: form.name.trim(),
          email: form.email.trim(),
          learningGoal: form.focus,
        })
      } catch (error) {
        const detail = error?.response?.data?.message || error?.message || '网络暂时不可用'
        toastError(`账号信息已保存在本机，云端同步暂未成功：${detail}。网络恢复后可再次保存同步。`)
        router.push(redirect)
        return
      }
    }

    router.push(redirect)
  } finally {
    isSubmitting.value = false
  }
}

async function submitFeedback() {
  if (feedbackSubmitting.value || feedback.message.trim().length < 5) return
  feedbackSubmitting.value = true
  try {
    await submitUserFeedback({
      category: feedback.category,
      message: feedback.message.trim(),
      contact: feedback.contact.trim() || undefined,
      pagePath: route.fullPath,
    })
    feedback.message = ''
    toastSuccess('反馈已提交，感谢你帮助我们改进体验。')
  } catch (error) {
    const detail = error?.response?.data?.message || '反馈提交失败，请稍后重试'
    toastError(detail)
  } finally {
    feedbackSubmitting.value = false
  }
}
</script>

<style scoped>
.profile-fill-view {
  display: grid;
  gap: var(--vt-space-6);
  width: 100%;
  min-width: 0;
  max-width: 1240px;
  overflow: hidden;
}

.account-overview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--vt-space-3);
  padding: 0 var(--vt-space-6);
}
.account-overview div {
  display: grid;
  gap: 0.25rem;
  padding: var(--vt-space-4);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-lg);
  background: var(--vt-bg-secondary);
}
.account-overview span {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}
.security-section {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
  margin: 0 var(--vt-space-6);
  padding: var(--vt-space-5);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-lg);
}
.security-section h2,
.security-section p {
  margin: 0.25rem 0 0;
}
.security-section p {
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
}
.security-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.field-hint {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.feedback-card {
  display: grid;
  gap: var(--vt-space-4);
  padding: var(--vt-space-6);
  border-top: 1px solid var(--vt-border-light);
}

.feedback-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-4);
}

.feedback-textarea {
  min-height: 132px;
  resize: vertical;
}

.profile-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-4);
  padding: var(--vt-space-6);
}

.preference-section {
  display: grid;
  gap: var(--vt-space-4);
  margin-top: var(--vt-space-2);
  padding: var(--vt-space-4);
  border: 1px solid rgba(13, 148, 136, 0.2);
  border-radius: var(--vt-radius-lg);
  background:
    radial-gradient(circle at 95% 0, rgba(13, 148, 136, 0.1), transparent 32%),
    rgba(13, 148, 136, 0.035);
}

.preference-section header {
  display: grid;
  gap: var(--vt-space-1);
}

.preference-section h2,
.preference-section p {
  margin: 0;
}

.preference-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-3);
}

.emotion-check {
  display: flex;
  align-items: flex-start;
  gap: var(--vt-space-2);
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-xs);
  line-height: 1.55;
}

.emotion-check input {
  margin-top: 0.25rem;
}

label {
  display: grid;
  gap: var(--vt-space-2);
}

.wide {
  grid-column: 1 / -1;
}

.form-error {
  margin: 0;
  color: var(--vt-accent-warm);
  font-weight: var(--vt-font-semibold);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--vt-space-3);
}

@media (max-width: 767px) {
  .account-overview {
    grid-template-columns: 1fr;
  }
  .security-section {
    align-items: stretch;
    flex-direction: column;
  }
  .profile-form {
    grid-template-columns: 1fr;
  }

  .preference-grid {
    grid-template-columns: 1fr;
  }

  .feedback-form {
    grid-template-columns: 1fr;
  }

  .form-actions {
    flex-direction: column-reverse;
  }
}
</style>
