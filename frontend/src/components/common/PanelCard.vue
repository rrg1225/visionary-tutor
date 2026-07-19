<template>
  <section
    class="vt-card"
    :class="$attrs.class"
    :aria-label="ariaLabel || undefined"
    v-bind="$attrs"
  >
    <slot />
  </section>
</template>

<script setup>
defineProps({
  ariaLabel: {
    type: String,
    default: ''
  }
})
</script>