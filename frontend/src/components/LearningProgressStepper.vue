<template>
  <section
    class="learning-progress-stepper vt-card"
    :class="{ 'learning-progress-stepper--done': showSlimStatus }"
    data-testid="learning-progress-stepper"
  >
    <template v-if="showSlimStatus">
      <div class="stepper-slim">
        <div class="stepper-slim-steps" aria-label="学习进度已完成">
          <span v-for="(step, index) in steps" :key="step.id" class="slim-step">
            <span class="slim-check" aria-hidden="true">✓</span>
            {{ step.label }}
            <span v-if="index < steps.length - 1" class="slim-divider" aria-hidden="true">·</span>
          </span>
        </div>
        <div class="stepper-slim-actions">
          <p class="vt-text-muted slim-hint">{{ hintText }}</p>
          <button
            type="button"
            class="vt-btn vt-btn-primary vt-btn-sm"
            @click="emit('open-report-panel')"
          >
            查看产出与报告
          </button>
        </div>
      </div>
    </template>

    <template v-else>
    <div class="vt-stepper" role="list" aria-label="学习进度">
      <div
        v-for="(step, index) in steps"
        :key="step.id"
        class="vt-step"
        :class="stepClass(index + 1)"
        role="listitem"
      >
        <span
          v-if="index < steps.length - 1"
          class="vt-step-connector"
          :class="{ 'vt-step-connector-active': stepStatus(index + 1) === 'done' }"
          aria-hidden="true"
        />
        <div
          class="vt-step-indicator"
          :aria-current="stepStatus(index + 1) === 'current' ? 'step' : undefined"
        >
          <span v-if="stepStatus(index + 1) === 'done'" aria-hidden="true">✓</span>
          <span v-else>{{ index + 1 }}</span>
        </div>
        <span class="vt-step-label">{{ step.label }}</span>
      </div>
    </div>

    <div v-if="hintText" class="stepper-hint">
      <p class="vt-text-muted">{{ hintText }}</p>
      <div v-if="showActions" class="stepper-actions">
        <RouterLink
          v-if="currentStep === 1"
          class="vt-btn vt-btn-primary vt-btn-sm"
          to="/auth?mode=register"
          data-testid="guest-register-banner"
        >
          免费注册
        </RouterLink>
        <RouterLink
          v-else-if="currentStep === 2"
          class="vt-btn vt-btn-primary vt-btn-sm"
          to="/onboarding"
          data-testid="no-profile-banner"
        >
          开始对话建档
        </RouterLink>
        <button
          v-else-if="currentStep === 3"
          type="button"
          class="vt-btn vt-btn-primary vt-btn-sm"
          :disabled="isGeneratingResources"
          data-testid="btn-stepper-generate-resources"
          @click="emit('generate-resources')"
        >
          {{ isGeneratingResources ? '生成中...' : '定制生成资源' }}
        </button>
        <button
          v-else-if="currentStep === 4"
          type="button"
          class="vt-btn vt-btn-ghost vt-btn-sm"
          @click="emit('open-report-panel')"
        >
          查看产出与报告
        </button>
      </div>
    </div>
    </template>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

const props = defineProps({
  isRegistered: {
    type: Boolean,
    default: false,
  },
  onboardingComplete: {
    type: Boolean,
    default: false,
  },
  hasResources: {
    type: Boolean,
    default: false,
  },
  isGeneratingResources: {
    type: Boolean,
    default: false,
  },
  showActions: {
    type: Boolean,
    default: true,
  },
})

const emit = defineEmits(['generate-resources', 'open-report-panel'])

const steps = [
  { id: 'register', label: '注册账号' },
  { id: 'onboarding', label: '对话建档' },
  { id: 'resources', label: '生成资源' },
  { id: 'report', label: '查看报告' },
]

const currentStep = computed(() => {
  if (!props.isRegistered) return 1
  if (!props.onboardingComplete) return 2
  if (!props.hasResources) return 3
  return 4
})

const showSlimStatus = computed(() => (
  currentStep.value === 4 && !props.isGeneratingResources
))

function stepStatus(stepNumber) {
  if (stepNumber < currentStep.value) return 'done'
  if (stepNumber === currentStep.value) return 'current'
  return 'locked'
}

function stepClass(stepNumber) {
  const status = stepStatus(stepNumber)
  return {
    'vt-step-active': status === 'current',
    'vt-step-completed': status === 'done',
    'vt-step-locked': status === 'locked',
  }
}

const hintText = computed(() => {
  switch (currentStep.value) {
    case 1:
      return '注册账号后可保存 7 维学习画像、生成 8 类 Agent 资源，并查看学习效果报告。'
    case 2:
      return '通过 3 分钟对话建立学习画像后，系统才能精准推荐资源与路径。'
    case 3:
      return props.isGeneratingResources
        ? '正在按你选择的主题与资源类型生成…'
        : '画像已就绪。在右侧「资源干预」填写主题、勾选资源类型后生成。'
    case 4:
      return props.isGeneratingResources
        ? '资源生成中，完成后请在右侧查看。'
        : '资源已在右侧，可继续对话或按需增量生成。'
    default:
      return ''
  }
})
</script>

<style scoped>
.learning-progress-stepper {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-3) var(--vt-space-4);
}

.learning-progress-stepper--done {
  padding: var(--vt-space-3) var(--vt-space-4);
}

.stepper-slim {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-3);
}

.stepper-slim-steps {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--vt-space-1);
  font-size: 11px;
  color: var(--vt-text-secondary);
}

.slim-step {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.slim-check {
  color: #16a34a;
  font-size: 10px;
  font-weight: var(--vt-font-bold);
}

.slim-divider {
  margin: 0 2px;
  color: var(--vt-text-tertiary);
}

.stepper-slim-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--vt-space-2);
  margin-left: auto;
}

.slim-hint {
  margin: 0;
  font-size: var(--vt-text-xs);
  max-width: 22rem;
}

.learning-progress-stepper--done .vt-stepper {
  opacity: 0.92;
}

.learning-progress-stepper--done .vt-step-label {
  font-size: 11px;
}

.vt-step-locked .vt-step-indicator {
  background: var(--vt-bg-secondary);
  color: var(--vt-text-tertiary);
  border-color: var(--vt-border-light);
  opacity: 0.72;
}

.vt-step-locked .vt-step-label {
  color: var(--vt-text-tertiary);
}

.stepper-hint {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--vt-space-2) var(--vt-space-3);
  padding-top: var(--vt-space-2);
  border-top: 1px solid var(--vt-border-light);
}

.stepper-hint p {
  margin: 0;
  flex: 1 1 12rem;
  min-width: 0;
  font-size: var(--vt-text-xs);
  line-height: 1.45;
}

.stepper-actions {
  display: flex;
  align-items: center;
  gap: var(--vt-space-2);
  flex: 0 0 auto;
}

.learning-progress-stepper--done .stepper-hint {
  border-top: none;
  padding-top: 0;
}
</style>
