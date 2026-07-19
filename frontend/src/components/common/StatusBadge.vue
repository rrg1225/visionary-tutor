<template>
  <span
    class="vt-badge"
    :class="variant ? `vt-badge-${variant}` : ''"
    :aria-label="label || undefined"
  >
    <slot>{{ fallbackText }}</slot>
  </span>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  variant: {
    type: String,
    default: '',
    validator: (v) => !v || ['success', 'warning', 'error', 'info'].includes(v)
  },
  label: {
    type: String,
    default: ''
  },
  code: {
    type: String,
    default: ''
  }
})

const fallbackText = computed(() => {
  if (props.variant === 'error' && props.code) {
    return `Agent 协作异常 [CODE: ${props.code}]`
  }
  return props.label || ''
})
</script>
