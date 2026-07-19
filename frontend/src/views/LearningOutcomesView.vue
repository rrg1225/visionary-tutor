<template>
  <section class="outcomes-page vt-container vt-section">
    <header class="outcomes-header">
      <div>
        <span class="vt-eyebrow">学习成果</span>
        <h1 class="vt-title">预览、组合并导出本次学习成果</h1>
        <p class="vt-text-muted">
          PPT
          编辑与导出集中在这里，不再占用资源生成页。导出内容来自当前学习会话中已经生成并通过发布检查的资源。
        </p>
      </div>
      <RouterLink class="vt-btn vt-btn-ghost vt-btn-sm" to="/my-learning">
        返回我的学习
      </RouterLink>
    </header>

    <div v-if="!authStore.isRegistered" class="vt-card outcome-gate">
      <h2>登录后管理学习成果</h2>
      <RouterLink
        class="vt-btn vt-btn-primary"
        :to="{
          path: '/auth',
          query: { mode: 'register', redirect: '/learning-outcomes' },
        }"
      >
        登录 / 注册
      </RouterLink>
    </div>

    <template v-else>
      <section class="vt-card outcome-summary">
        <div>
          <span>当前学习会话</span>
          <strong>{{
            learningSession.currentSession?.topic || "尚未创建学习会话"
          }}</strong>
        </div>
        <div>
          <span>可组合资源</span>
          <strong>{{ exportableResources.length }} 项</strong>
        </div>
        <div>
          <span>导出方式</span>
          <strong>预览编辑 / 标准版 / 精美版</strong>
        </div>
      </section>

      <section class="vt-card export-panel">
        <header>
          <div>
            <span class="vt-eyebrow">成果导出</span>
            <h2>先预览内容，再决定导出版本</h2>
          </div>
          <span v-if="!exportableResources.length" class="export-hint"
            >当前会话还没有可导出的资源</span
          >
        </header>
        <div class="export-actions">
          <button
            type="button"
            class="vt-btn vt-btn-primary"
            :disabled="!canExport"
            @click="showEditor = true"
          >
            预览 / 编辑 PPT
          </button>
          <button
            type="button"
            class="vt-btn vt-btn-outline"
            :disabled="!canExport || exportLoading"
            @click="exportPptx('standard')"
          >
            {{
              exportLoading && exportQuality === "standard"
                ? "导出中…"
                : "导出标准版"
            }}
          </button>
          <button
            type="button"
            class="vt-btn vt-btn-outline"
            :disabled="!canExport || exportLoading"
            @click="exportPptx('premium')"
          >
            {{
              exportLoading && exportQuality === "premium"
                ? "导出中…"
                : "导出精美版"
            }}
          </button>
        </div>
      </section>

      <section class="resource-index">
        <header>
          <span class="vt-eyebrow">本次内容</span>
          <h2>将进入成果组合的资源</h2>
        </header>
        <div v-if="exportableResources.length" class="resource-list">
          <article
            v-for="item in exportableResources"
            :key="item.id || item.artifactType"
            class="vt-card"
          >
            <span>{{ item.type || item.artifactType }}</span>
            <strong>{{ item.title }}</strong>
            <p>
              {{ item.summary || "已生成内容，可在预览中调整标题与正文。" }}
            </p>
          </article>
        </div>
        <div v-else class="vt-card empty-outcomes">
          <strong>还没有可组合的学习成果</strong>
          <p>先在学习资料、题库、互动实验或学习规划中完成一项内容。</p>
          <RouterLink class="vt-btn vt-btn-primary vt-btn-sm" to="/resources">
            选择要创建的资源
          </RouterLink>
        </div>
      </section>
    </template>

    <PptxSlideEditor
      :open="showEditor"
      :session-id="learningSession.currentSessionId"
      :resources="exportableResources"
      @close="showEditor = false"
    />
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import PptxSlideEditor from "../components/PptxSlideEditor.vue";
import { downloadSessionPptx } from "../api/resources";
import { useAuthStore } from "../stores/authStore";
import { useLearningSessionStore } from "../stores/learningSession";

const authStore = useAuthStore();
const learningSession = useLearningSessionStore();
const showEditor = ref(false);
const exportLoading = ref(false);
const exportQuality = ref("standard");

const exportableResources = computed(() =>
  learningSession.resourceCards.filter(
    (item) =>
      !item.isShowcase &&
      item.publishStatus !== "BLOCKED" &&
      item.artifactType !== "VIDEO_SCRIPT",
  ),
);
const canExport = computed(() =>
  Boolean(learningSession.currentSessionId && exportableResources.value.length),
);

async function exportPptx(quality) {
  if (!canExport.value || exportLoading.value) return;
  exportQuality.value = quality;
  await downloadSessionPptx(
    String(learningSession.currentSessionId),
    quality,
    exportLoading,
  );
}

onMounted(async () => {
  if (!authStore.isRegistered) return;
  await learningSession.ensureCurrentSession("学习成果");
  await learningSession.hydrateGeneratedResources();
});
</script>

<style scoped>
.outcomes-page,
.resource-index {
  display: grid;
  gap: var(--vt-space-5);
}

.outcomes-page {
  max-width: 1080px;
}

.outcomes-header,
.export-panel header,
.export-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
}

.outcomes-header p {
  max-width: 760px;
  line-height: 1.7;
}

.outcome-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--vt-space-3);
  padding: var(--vt-space-5);
}

.outcome-summary div {
  display: grid;
  gap: var(--vt-space-2);
}

.outcome-summary span,
.resource-list span,
.export-hint {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.export-panel,
.outcome-gate,
.empty-outcomes {
  display: grid;
  gap: var(--vt-space-4);
  padding: var(--vt-space-5);
}

.export-panel h2,
.resource-index h2 {
  margin: 0;
  color: var(--vt-text-primary);
  font-size: var(--vt-text-lg);
}

.export-actions {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.resource-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-4);
}

.resource-list article {
  display: grid;
  gap: var(--vt-space-2);
  padding: var(--vt-space-4);
}

.resource-list p,
.empty-outcomes p {
  margin: 0;
  color: var(--vt-text-secondary);
  line-height: 1.6;
}

@media (max-width: 760px) {
  .outcomes-header,
  .export-panel header {
    align-items: stretch;
    flex-direction: column;
  }

  .outcome-summary,
  .resource-list {
    grid-template-columns: 1fr;
  }
}
</style>
