<template>
  <!-- Content is sanitized before rendering. -->
  <!-- eslint-disable-next-line vue/no-v-html -->
  <div ref="documentRoot" class="document-resource">
    <div
      class="markdown-body"
      @mouseup="captureSelection"
      @keyup="captureSelection"
      v-html="safeHtml"
    />
    <MermaidViewer
      v-for="(diagram, index) in mermaidDiagrams"
      :key="index"
      :content="diagram"
    />

    <div v-if="enableTutor" class="reading-tutor-entry">
      <div>
        <strong>{{
          selectedText ? "已选中一段正文" : "阅读中有疑问？"
        }}</strong>
        <p>
          {{
            selectedText
              ? selectionPreview
              : "可直接针对整篇内容向 AI 老师提问，也可以先划选一段文字。"
          }}
        </p>
      </div>
      <button
        type="button"
        class="vt-btn vt-btn-outline vt-btn-sm"
        @click="showTutor = !showTutor"
      >
        {{
          showTutor
            ? "收起 AI 老师"
            : selectedText
              ? "就这段问 AI 老师"
              : "打开 AI 老师"
        }}
      </button>
    </div>

    <ContextualTutorPanel
      v-if="enableTutor && showTutor"
      :title="selectedText ? '解释选中的阅读内容' : '拓展阅读 AI 老师'"
      :context="tutorContext"
      :learning-session-id="learningSessionId"
      :context-type="contextType"
      :context-key="contextKey"
      :context-title="title"
      :suggested-question="
        selectedText
          ? '请解释这段内容，并说明它和本章主题的关系。'
          : '请帮我梳理这篇材料的核心知识和学习重点。'
      "
      @close="showTutor = false"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, ref } from "vue";
import "katex/dist/katex.min.css";
import { renderSimpleMarkdown } from "../../../utils/simpleMarkdown";
import { sanitizeAssistantContent } from "../../../utils/sanitizeAssistantContent";

const props = withDefaults(
  defineProps<{
    content?: string;
    title?: string;
    enableTutor?: boolean;
    learningSessionId?: number | null;
    contextType?: string;
    contextKey?: string;
  }>(),
  {
    content: "",
    title: "拓展阅读",
    enableTutor: false,
    learningSessionId: null,
    contextType: "LEARNING_MATERIAL",
    contextKey: "",
  },
);
const MermaidViewer = defineAsyncComponent(
  () => import("../../MermaidViewer.vue"),
);
const ContextualTutorPanel = defineAsyncComponent(
  () => import("../../ContextualTutorPanel.vue"),
);
const documentRoot = ref<HTMLElement | null>(null);
const selectedText = ref("");
const showTutor = ref(false);
const sanitizedContent = computed(() =>
  sanitizeAssistantContent(props.content).replace(
    /^---\s*\n[\s\S]*?\n---\s*\n?/,
    "",
  ),
);
const mermaidDiagrams = computed(() =>
  Array.from(
    sanitizedContent.value.matchAll(/```mermaid\s*([\s\S]*?)```/gi),
    (match) => match[1].trim(),
  ).filter(Boolean),
);
const safeHtml = computed(() =>
  renderSimpleMarkdown(
    sanitizedContent.value.replace(/```mermaid\s*[\s\S]*?```/gi, ""),
  ),
);
const selectionPreview = computed(() => {
  const text = selectedText.value.replace(/\s+/g, " ").trim();
  return text.length > 120 ? `${text.slice(0, 120)}…` : text;
});
const tutorContext = computed(() => {
  const document = sanitizedContent.value.slice(0, 14_000);
  if (!selectedText.value) {
    return `材料标题：${props.title}\n\n${document}`;
  }
  return `材料标题：${props.title}\n\n用户选中的段落：\n${selectedText.value}\n\n完整材料上下文：\n${document}`;
});

function captureSelection() {
  const selection = globalThis.getSelection?.();
  const anchorNode = selection?.anchorNode;
  if (
    !selection ||
    selection.isCollapsed ||
    !anchorNode ||
    !documentRoot.value?.contains(anchorNode)
  )
    return;
  const text = selection.toString().replace(/\s+/g, " ").trim();
  if (text.length >= 2) selectedText.value = text.slice(0, 2_000);
}
</script>

<style scoped>
.document-resource {
  display: grid;
  gap: var(--vt-space-3);
  min-width: 0;
}

.reading-tutor-entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-3);
  padding: var(--vt-space-3);
  border: 1px solid rgba(13, 148, 136, 0.22);
  border-radius: var(--vt-radius-md);
  background: rgba(13, 148, 136, 0.05);
}

.reading-tutor-entry strong {
  color: var(--vt-text-primary);
  font-size: var(--vt-text-sm);
}

.reading-tutor-entry p {
  margin: var(--vt-space-1) 0 0;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-xs);
  line-height: 1.5;
}

@media (max-width: 640px) {
  .reading-tutor-entry {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
