<template>
  <PanelCard class="generated-handout" aria-label="生成讲义">
    <PageHeader>
      <template #eyebrow>资源生成</template>
      <template #badge>
        <StatusBadge variant="success" class="handout-badge">真实内容</StatusBadge>
      </template>
      <template #title>生成讲义</template>
      <template #desc>仅渲染流式生成或后端落库后的内容。</template>
    </PageHeader>

    <article class="handout-body" aria-live="polite">
      <MarkdownPanel :content="displayMarkdown" />
    </article>
  </PanelCard>
</template>

<script setup>
import { computed } from 'vue'
import { sanitizeAssistantContent } from '../utils/sanitizeAssistantContent'
import PanelCard from './common/PanelCard.vue'
import PageHeader from './common/PageHeader.vue'
import StatusBadge from './common/StatusBadge.vue'
import MarkdownPanel from './common/MarkdownPanel.vue'

const props = defineProps({
  content: {
    type: String,
    default: '',
  },
  text: {
    type: String,
    default: '',
  },
})

const displayMarkdown = computed(() => sanitizeAssistantContent((props.content || props.text || '').trim()))
</script>

<style scoped>
.generated-handout {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-5);
  padding: var(--vt-space-5);
  min-height: 240px;
}

.handout-badge {
  font-size: 10px;
}

.handout-body {
  min-width: 0;
}
</style>
