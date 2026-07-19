<template>
  <Teleport to="body">
    <div v-if="active && current" class="tour-root" role="dialog" aria-modal="true" :aria-label="current.title">
      <div class="tour-backdrop" aria-hidden="true"></div>
      <div
        v-if="highlightStyle"
        class="tour-highlight"
        :style="highlightStyle"
        aria-hidden="true"
      ></div>

      <section class="tour-card vt-card-elevated">
        <div class="tour-progress" aria-label="引导进度">
          <span>新手引导</span>
          <strong>{{ modelValue + 1 }}/{{ steps.length }}</strong>
        </div>
        <h2>{{ current.title }}</h2>
        <p>{{ current.description }}</p>

        <div class="tour-actions">
          <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm" @click="$emit('skip')">
            跳过引导
          </button>
          <div class="tour-actions-main">
            <button
              v-if="modelValue > 0"
              type="button"
              class="vt-btn vt-btn-outline vt-btn-sm"
              @click="move(-1)"
            >
              上一步
            </button>
            <button type="button" class="vt-btn vt-btn-primary vt-btn-sm" @click="move(1)">
              {{ modelValue >= steps.length - 1 ? '完成引导' : '下一步' }}
            </button>
          </div>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, watch } from 'vue'

const props = defineProps({
  active: { type: Boolean, default: false },
  steps: { type: Array, default: () => [] },
  modelValue: { type: Number, default: 0 },
})

const emit = defineEmits(['update:modelValue', 'complete', 'skip'])

const current = computed(() => props.steps[props.modelValue] || null)
const highlightStyle = computed(() => current.value?.rect
  ? {
      top: `${current.value.rect.top - 8}px`,
      left: `${current.value.rect.left - 8}px`,
      width: `${current.value.rect.width + 16}px`,
      height: `${current.value.rect.height + 16}px`,
    }
  : null)

function resolveTarget() {
  const step = current.value
  if (!step) return
  const target = step.selector ? document.querySelector(step.selector) : null
  if (!target) {
    step.rect = null
    return
  }
  target.scrollIntoView({ behavior: 'smooth', block: 'center' })
  window.setTimeout(() => {
    step.rect = target.getBoundingClientRect()
  }, 220)
}

function move(delta) {
  if (delta > 0 && props.modelValue >= props.steps.length - 1) {
    emit('complete')
    return
  }
  emit('update:modelValue', Math.max(0, Math.min(props.steps.length - 1, props.modelValue + delta)))
}

watch(
  () => [props.active, props.modelValue],
  async () => {
    if (!props.active) return
    await nextTick()
    resolveTarget()
  },
  { immediate: true },
)

onMounted(() => window.addEventListener('resize', resolveTarget))
onBeforeUnmount(() => window.removeEventListener('resize', resolveTarget))
</script>

<style scoped>
.tour-root {
  position: fixed;
  inset: 0;
  z-index: 9999;
  pointer-events: none;
}

.tour-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.48);
}

.tour-highlight {
  position: fixed;
  z-index: 1;
  border: 3px solid #5eead4;
  border-radius: 18px;
  box-shadow: 0 0 0 9999px rgba(15, 23, 42, 0.16), 0 0 32px rgba(45, 212, 191, 0.5);
  transition: all 180ms ease;
}

.tour-card {
  position: fixed;
  z-index: 2;
  left: 50%;
  bottom: max(24px, env(safe-area-inset-bottom));
  width: min(520px, calc(100vw - 32px));
  transform: translateX(-50%);
  padding: var(--vt-space-5);
  pointer-events: auto;
}

.tour-progress,
.tour-actions,
.tour-actions-main {
  display: flex;
  align-items: center;
}

.tour-progress,
.tour-actions {
  justify-content: space-between;
  gap: var(--vt-space-3);
}

.tour-progress {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.tour-card h2 {
  margin: var(--vt-space-3) 0 var(--vt-space-2);
  color: var(--vt-text-primary);
  font-size: 1.25rem;
}

.tour-card p {
  margin: 0 0 var(--vt-space-5);
  color: var(--vt-text-secondary);
  line-height: 1.7;
}

.tour-actions-main {
  gap: var(--vt-space-2);
}

@media (max-width: 560px) {
  .tour-card {
    padding: var(--vt-space-4);
  }

  .tour-actions {
    align-items: stretch;
    flex-direction: column-reverse;
  }

  .tour-actions-main > * {
    flex: 1;
  }
}
</style>
