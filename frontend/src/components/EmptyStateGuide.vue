<template>
  <div
    class="empty-state-guide vt-card"
    :class="{ compact, centered }"
    role="status"
  >
    <div class="empty-state-visual" aria-hidden="true">
      <slot name="icon">
        <img v-if="imageSrc" :src="imageSrc" :alt="''" class="empty-state-image" />
        <component v-else :is="iconComponent" class="empty-state-icon" />
      </slot>
    </div>

    <div class="empty-state-copy">
      <h2 v-if="title" class="empty-state-title">{{ title }}</h2>
      <p v-if="description" class="empty-state-desc">{{ description }}</p>
      <slot />
    </div>

    <div v-if="hasActions" class="empty-state-actions">
      <slot name="actions">
        <RouterLink
          v-if="ctaTo"
          class="vt-btn vt-btn-primary"
          :to="ctaTo"
        >
          {{ ctaText }}
        </RouterLink>
        <button
          v-else-if="ctaText"
          type="button"
          class="vt-btn vt-btn-primary"
          @click="$emit('cta-click')"
        >
          {{ ctaText }}
        </button>
        <RouterLink
          v-if="secondaryCtaTo"
          class="vt-btn vt-btn-outline"
          :to="secondaryCtaTo"
        >
          {{ secondaryCtaText }}
        </RouterLink>
        <button
          v-else-if="secondaryCtaText"
          type="button"
          class="vt-btn vt-btn-outline"
          @click="$emit('secondary-cta-click')"
        >
          {{ secondaryCtaText }}
        </button>
      </slot>
    </div>
  </div>
</template>

<script setup>
import { computed, h } from 'vue'
import { RouterLink } from 'vue-router'

const props = defineProps({
  title: {
    type: String,
    default: '',
  },
  description: {
    type: String,
    default: '',
  },
  icon: {
    type: String,
    default: 'default',
  },
  imageSrc: {
    type: String,
    default: '',
  },
  ctaText: {
    type: String,
    default: '',
  },
  ctaTo: {
    type: [String, Object],
    default: '',
  },
  secondaryCtaText: {
    type: String,
    default: '',
  },
  secondaryCtaTo: {
    type: [String, Object],
    default: '',
  },
  compact: {
    type: Boolean,
    default: false,
  },
  centered: {
    type: Boolean,
    default: true,
  },
})

defineEmits(['cta-click', 'secondary-cta-click'])

const hasActions = computed(() =>
  Boolean(props.ctaText || props.secondaryCtaText)
)

const iconPaths = {
  profile: 'M12 11a4 4 0 1 0-4-4 4 4 0 0 0 4 4Zm0 2c-4.42 0-8 2.24-8 5v1h16v-1c0-2.76-3.58-5-8-5Z',
  report: 'M6 4h9l3 3v13a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Zm8 1.5V8h2.5M8 12h8M8 16h5',
  diagnosis: 'M9 3h6l1 2h3a1 1 0 0 1 1 1v3.5l-2 2v8.5a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-8.5l-2-2V6a1 1 0 0 1 1-1h3l1-2Zm3 8a2.5 2.5 0 1 0 2.5 2.5A2.5 2.5 0 0 0 12 11Z',
  assessment: 'M7 3h10a2 2 0 0 1 2 2v14l-4-2-4 2-4-2-4 2V5a2 2 0 0 1 2-2Z',
  default: 'M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2Zm0 5v5l4 2',
}

const iconComponent = computed(() => {
  const path = iconPaths[props.icon] || iconPaths.default
  return {
    render() {
      return h(
        'svg',
        {
          viewBox: '0 0 24 24',
          fill: 'none',
          stroke: 'currentColor',
          'stroke-width': '1.6',
          'stroke-linecap': 'round',
          'stroke-linejoin': 'round',
        },
        [h('path', { d: path })],
      )
    },
  }
})
</script>

<style scoped>
.empty-state-guide {
  display: grid;
  gap: var(--vt-space-4);
  padding: var(--vt-space-8);
  border: 1px dashed rgba(148, 163, 184, 0.45);
  background: linear-gradient(180deg, rgba(248, 250, 252, 0.95), rgba(241, 245, 249, 0.65));
}

.empty-state-guide.compact {
  padding: var(--vt-space-5);
  gap: var(--vt-space-3);
}

.empty-state-guide.centered {
  justify-items: center;
  text-align: center;
}

.empty-state-visual {
  display: grid;
  place-items: center;
  width: 72px;
  height: 72px;
  border-radius: var(--vt-radius-full);
  background: rgba(59, 130, 246, 0.1);
  color: var(--vt-accent-primary);
}

.empty-state-guide.compact .empty-state-visual {
  width: 56px;
  height: 56px;
}

.empty-state-icon {
  width: 34px;
  height: 34px;
}

.empty-state-guide.compact .empty-state-icon {
  width: 28px;
  height: 28px;
}

.empty-state-image {
  width: 48px;
  height: 48px;
  object-fit: contain;
}

.empty-state-copy {
  display: grid;
  gap: var(--vt-space-2);
  max-width: 36rem;
}

.empty-state-title {
  margin: 0;
  font-size: var(--vt-text-lg);
  color: var(--vt-text-primary);
}

.empty-state-guide.compact .empty-state-title {
  font-size: var(--vt-text-base);
}

.empty-state-desc {
  margin: 0;
  font-size: var(--vt-text-sm);
  color: var(--vt-text-secondary);
  line-height: 1.65;
}

.empty-state-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-3);
  justify-content: center;
}
</style>
