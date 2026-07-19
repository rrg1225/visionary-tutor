<template>
  <section class="profile-view vt-card vt-container-narrow">
    <header class="page-header">
      <span class="vt-eyebrow">学生档案</span>
      <h1 class="vt-title">注册与学习偏好</h1>
      <p class="vt-text-muted">检查您的档案摘要。使用专用页面编辑 — 无需弹窗。</p>
    </header>

    <EmptyStateGuide
      v-if="!userProfile.isProfileComplete"
      icon="profile"
      :title="profileGateTitle"
      :description="profileGateDescription"
      cta-text="前往建档"
      cta-to="/onboarding"
      secondary-cta-text="账号设置"
      secondary-cta-to="/profile-fill"
    />

    <dl class="profile-summary vt-card">
      <div>
        <dt class="vt-label">姓名</dt>
        <dd>{{ userProfile.name || '—' }}</dd>
      </div>
      <div>
        <dt class="vt-label">邮箱</dt>
        <dd>{{ userProfile.email || '—' }}</dd>
      </div>
      <div>
        <dt class="vt-label">当前专注领域</dt>
        <dd>{{ userProfile.focus }}</dd>
      </div>
      <div>
        <dt class="vt-label">解释风格偏好</dt>
        <dd>{{ userProfile.style }}</dd>
      </div>
      <div>
        <dt class="vt-label">AI 老师语气</dt>
        <dd>{{ userProfile.teacherTone }} · {{ userProfile.responseDetail }}</dd>
      </div>
      <div>
        <dt class="vt-label">回答结构</dt>
        <dd>{{ userProfile.answerStructure }} · 鼓励{{ userProfile.encouragementLevel }}</dd>
      </div>
      <div class="wide">
        <dt class="vt-label">学习目标</dt>
        <dd>{{ userProfile.goal || '—' }}</dd>
      </div>
    </dl>

    <section class="profile-dimensions vt-card" aria-label="动态学习画像">
      <header>
        <span class="vt-eyebrow">动态学习画像</span>
        <p class="vt-text-muted">画像会根据对话与作业评测自动更新。</p>
        <p v-if="userProfile.lastChangedDimensions.length" class="profile-change-banner">
          最近更新维度：{{ userProfile.lastChangedDimensions.join('、') }}
        </p>
        <p v-if="userProfile.extractionMessage" class="profile-extract-msg">{{ userProfile.extractionMessage }}</p>
      </header>
      <div class="dimension-grid">
        <article
          v-for="item in userProfile.profileDimensions"
          :key="item.key"
          class="dimension-item"
          :class="{
            changed: userProfile.lastChangedDimensions.includes(item.label),
            pending: isPendingProfileDimensionValue(item.value),
          }"
          :title="isPendingProfileDimensionValue(item.value) ? '完成作业后更新' : undefined"
        >
          <span>{{ item.label }}</span>
          <strong>{{ item.value }}</strong>
        </article>
      </div>
    </section>

    <div class="profile-actions">
      <RouterLink class="vt-btn vt-btn-outline" to="/learn">返回学习循环</RouterLink>
      <RouterLink class="vt-btn vt-btn-outline" to="/memory">记忆管理</RouterLink>
      <RouterLink class="vt-btn vt-btn-outline" to="/learning-report">学习效果报告</RouterLink>
      <RouterLink class="vt-btn vt-btn-outline" to="/profile-fill">账号设置</RouterLink>
      <button type="button" class="vt-btn vt-btn-primary" @click="startOnboarding">
        {{ userProfile.isProfileComplete ? '重新对话建档' : '开始对话建档' }}
      </button>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import EmptyStateGuide from '../components/EmptyStateGuide.vue'
import { isPendingProfileDimensionValue, useUserProfileStore } from '../stores/userProfile'

const userProfile = useUserProfileStore()
const router = useRouter()
const profileGateTitle = computed(() => (
  userProfile.onboardingAnswerCount > 0
    ? `画像建档中（${Math.min(userProfile.onboardingAnswerCount, 4)}/4）`
    : '学习画像尚未建立'
))
const profileGateDescription = computed(() => (
  userProfile.onboardingAnswerCount > 0
    ? `已识别 ${userProfile.filledDimensionCount}/7 维，继续完成对话即可保存学习节奏并解锁个性化资源。`
    : '完成 3 分钟对话建档后，系统会自动抽取 7 维学习特征，并解锁个性化资源推荐。'
))

function startOnboarding() {
  if (userProfile.isProfileComplete) {
    userProfile.restartOnboarding()
  }
  router.push('/onboarding')
}

onMounted(() => {
  userProfile.hydrateFromStorage()
})
</script>

<style scoped>
.profile-view {
  display: grid;
  gap: var(--vt-space-6);
  width: 100%;
  min-width: 0;
  padding: var(--vt-space-6);
}

.profile-view .page-header {
  margin-bottom: 0;
}

.profile-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-4);
  padding: var(--vt-space-6);
}

.profile-summary div {
  display: grid;
  gap: var(--vt-space-1);
}

.profile-summary dd {
  margin: 0;
  font-weight: var(--vt-font-medium);
}

.profile-dimensions {
  display: grid;
  gap: var(--vt-space-4);
  padding: var(--vt-space-6);
}

.profile-dimensions header {
  display: grid;
  gap: var(--vt-space-1);
}

.dimension-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-3);
}

.dimension-item {
  display: grid;
  gap: var(--vt-space-1);
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
}

.dimension-item span {
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
}

.dimension-item strong {
  font-size: var(--vt-text-sm);
  color: var(--vt-text-primary);
}

.dimension-item.changed {
  border: 1px solid rgba(59, 130, 246, 0.35);
  background: rgba(59, 130, 246, 0.06);
}

.dimension-item.pending {
  border: 1px dashed var(--vt-border-medium);
  background: var(--vt-bg-primary);
  cursor: help;
}

.dimension-item.pending strong {
  color: var(--vt-text-tertiary);
}

.profile-change-banner {
  margin: 0;
  padding: var(--vt-space-2) var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: rgba(59, 130, 246, 0.08);
  color: var(--vt-accent-primary);
  font-size: var(--vt-text-sm);
}

.profile-extract-msg {
  margin: 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
}

.wide {
  grid-column: 1 / -1;
}

.profile-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-3);
}

@media (max-width: 767px) {
  .profile-summary {
    grid-template-columns: 1fr;
  }

  .dimension-grid {
    grid-template-columns: 1fr;
  }

  .profile-actions {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
