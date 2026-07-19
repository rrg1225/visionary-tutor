<template>
  <div class="markdown-body" v-html="safeContent" aria-live="polite"></div>
</template>

<script setup>
import { computed } from 'vue'
import { renderSimpleMarkdown } from '../../utils/simpleMarkdown'

const props = defineProps({
  content: {
    type: String,
    default: ''
  }
})

const safeContent = computed(() => {
  const src = props.content
  if (!src || typeof src !== 'string' || !src.trim) return ''
  return renderSimpleMarkdown(src.trim())
})
</script>
