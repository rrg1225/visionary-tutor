<template>
  <section class="my-learning vt-container vt-section">
    <header class="my-learning-header vt-card">
      <div class="hero-copy">
        <span class="vt-eyebrow">我的学习</span>
        <h1 class="vt-title">从最近进度继续，而不是重新寻找入口</h1>
        <p class="vt-text-muted">
          这里汇总个人资源、学习规划、报告、成果和账户设置。学习任务本身仍在教材、题库和互动实验中完成。
        </p>
      </div>
      <div class="hero-side">
        <section class="current-session" aria-label="当前学习会话">
          <span class="vt-eyebrow">当前学习会话</span>
          <strong>{{
            learningSession.currentSession?.topic ||
            "登录后自动恢复最近一次学习"
          }}</strong>
          <p>{{ sessionDescription }}</p>
        </section>
        <RouterLink class="vt-btn vt-btn-primary" :to="continueTarget">
          继续上次学习
        </RouterLink>
      </div>
    </header>

    <section class="learning-overview">
      <header class="overview-heading">
        <div>
          <span class="vt-eyebrow">常用入口</span>
          <h2>按目标进入，不在页面之间来回寻找</h2>
        </div>
      </header>
      <div class="learning-grid">
        <RouterLink
          v-for="item in learningEntries"
          :key="item.to"
          :to="item.to"
          class="learning-card vt-card"
        >
          <span class="entry-icon" aria-hidden="true">{{ item.icon }}</span>
          <div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.description }}</p>
          </div>
          <span class="entry-arrow" aria-hidden="true">→</span>
        </RouterLink>
      </div>
    </section>

    <section class="learning-history vt-card">
      <header class="history-heading">
        <div>
          <span class="vt-eyebrow">统一学习历史</span>
          <h2>资源、题卷和状态报告集中查找</h2>
        </div>
        <span>{{ filteredHistory.length }} 条</span>
      </header>
      <div class="history-filters">
        <select
          v-model="historyType"
          class="vt-input"
          aria-label="学习历史类型"
        >
          <option value="">全部类型</option>
          <option value="resource">AI 资源</option>
          <option value="exam">固定题卷报告</option>
          <option value="state">学习状态报告</option>
        </select>
        <input
          v-model.trim="historyQuery"
          class="vt-input"
          type="search"
          placeholder="搜索标题、主题或类型"
        />
        <label class="favorite-filter">
          <input v-model="favoriteOnly" type="checkbox" /> 只看收藏
        </label>
      </div>
      <div v-if="pagedHistory.length" class="history-list">
        <article
          v-for="item in pagedHistory"
          :key="item.key"
          class="history-row"
        >
          <RouterLink :to="item.to" class="history-main">
            <span>{{ item.typeLabel }}</span>
            <div>
              <strong>{{ displayTitle(item) }}</strong>
              <small
                >{{ item.subtitle }} · {{ formatDate(item.createdAt) }}</small
              >
            </div>
          </RouterLink>
          <div class="history-actions">
            <button
              type="button"
              :title="isFavorite(item) ? '取消收藏' : '收藏'"
              @click="toggleFavorite(item)"
            >
              {{ isFavorite(item) ? "★" : "☆" }}
            </button>
            <button
              type="button"
              title="重命名显示标题"
              @click="renameItem(item)"
            >
              ✎
            </button>
            <button type="button" title="从历史中隐藏" @click="hideItem(item)">
              ×
            </button>
          </div>
        </article>
      </div>
      <p v-else class="history-empty">没有匹配的学习历史。</p>
      <footer v-if="totalPages > 1" class="history-pagination">
        <button
          type="button"
          class="vt-btn vt-btn-ghost vt-btn-sm"
          :disabled="historyPage <= 1"
          @click="historyPage -= 1"
        >
          上一页
        </button>
        <span>{{ historyPage }} / {{ totalPages }}</span>
        <button
          type="button"
          class="vt-btn vt-btn-ghost vt-btn-sm"
          :disabled="historyPage >= totalPages"
          @click="historyPage += 1"
        >
          下一页
        </button>
      </footer>
    </section>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { listFixedExamReports } from "../api/fixedExams";
import { listLearningStateReports } from "../api/learningState";
import { useResourceLibrary } from "../composables/useResourceLibrary";
import { useAuthStore } from "../stores/authStore";
import { useLearningSessionStore } from "../stores/learningSession";

const authStore = useAuthStore();
const learningSession = useLearningSessionStore();
const { libraryResources, loadLibrary } = useResourceLibrary();
const fixedReports = ref([]);
const stateReports = ref([]);
const historyType = ref("");
const historyQuery = ref("");
const favoriteOnly = ref(false);
const historyPage = ref(1);
const favoriteKeys = ref([]);
const hiddenKeys = ref([]);
const titleAliases = ref({});
const HISTORY_PAGE_SIZE = 8;

const learningEntries = [
  {
    icon: "📚",
    title: "AI 资源中心",
    description: "查看各类资源数量、最近内容和创建入口。",
    to: "/resources",
  },
  {
    icon: "🧭",
    title: "学习规划",
    description: "维护知识导图与下一步学习路径。",
    to: "/learning-plan",
  },
  {
    icon: "📈",
    title: "学习报告",
    description: "查看掌握度、薄弱点和阶段改进建议。",
    to: "/learning-report",
  },
  {
    icon: "🏆",
    title: "学习成果",
    description: "组合、预览并导出本次学习成果。",
    to: "/learning-outcomes",
  },
  {
    icon: "👤",
    title: "个人中心",
    description: "设置学习目标、回答偏好与账户信息。",
    to: "/profile-fill",
  },
  {
    icon: "🔐",
    title: "隐私与记忆",
    description: "管理学习记忆、摄像头策略和数据导出。",
    to: "/privacy",
  },
];

const continueTarget = computed(() =>
  authStore.isRegistered
    ? "/learn"
    : "/auth?mode=register&redirect=/my-learning",
);
const sessionDescription = computed(() => {
  if (!authStore.isRegistered)
    return "注册后可跨页面保存对话、资源、错题和学习报告。";
  if (!learningSession.currentSessionId)
    return "还没有学习会话，可以先向 AI 老师提出一个真实问题。";
  return `会话 #${learningSession.currentSessionId} 已关联对话、资源和测评记录。`;
});

const historyStoragePrefix = computed(
  () => `vt_my_learning_history:${authStore.currentUserId || "guest"}`,
);
const allHistory = computed(() =>
  [
    ...libraryResources.value
      .filter(
        (item) => !item.isShowcase && item.artifactType !== "VIDEO_SCRIPT",
      )
      .map((item) => ({
        key: `resource:${item.id || `${item.artifactType}-${item.runId}`}`,
        category: "resource",
        typeLabel: resourceTypeLabel(item.artifactType),
        title: item.title || "未命名 AI 资源",
        subtitle: item.sessionTopic || "个性化学习资源",
        createdAt: item.gmtCreated || item.createdAt || Number(item.id) || 0,
        to: targetForResource(item.artifactType),
      })),
    ...fixedReports.value.map((item) => ({
      key: `exam:${item.reportId || item.attemptId}`,
      category: "exam",
      typeLabel: "固定题卷",
      title: item.paperTitle || item.paperCode || "固定题卷报告",
      subtitle: `得分 ${item.totalScore ?? "—"}/${item.maxScore ?? "—"} · 正确率 ${item.accuracyPercent ?? 0}%`,
      createdAt: item.submittedAt,
      to: `/questions/attempts/${item.attemptId}/report`,
    })),
    ...stateReports.value.map((item) => ({
      key: `state:${item.id}`,
      category: "state",
      typeLabel: "状态报告",
      title: item.contextTitle || item.headline || "学习状态报告",
      subtitle: item.headline || `${item.durationSeconds || 0} 秒有效观察`,
      createdAt: item.createdAt,
      to: "/learning-report",
    })),
  ]
    .filter((item) => !hiddenKeys.value.includes(item.key))
    .sort(
      (a, b) =>
        new Date(b.createdAt || 0).getTime() -
        new Date(a.createdAt || 0).getTime(),
    ),
);
const filteredHistory = computed(() => {
  const query = historyQuery.value.toLowerCase();
  return allHistory.value.filter((item) => {
    if (historyType.value && item.category !== historyType.value) return false;
    if (favoriteOnly.value && !favoriteKeys.value.includes(item.key))
      return false;
    return (
      !query ||
      `${displayTitle(item)} ${item.subtitle} ${item.typeLabel}`
        .toLowerCase()
        .includes(query)
    );
  });
});
const totalPages = computed(() =>
  Math.max(1, Math.ceil(filteredHistory.value.length / HISTORY_PAGE_SIZE)),
);
const pagedHistory = computed(() =>
  filteredHistory.value.slice(
    (historyPage.value - 1) * HISTORY_PAGE_SIZE,
    historyPage.value * HISTORY_PAGE_SIZE,
  ),
);

watch([historyType, historyQuery, favoriteOnly], () => {
  historyPage.value = 1;
});
watch(totalPages, (pages) => {
  if (historyPage.value > pages) historyPage.value = pages;
});

function resourceTypeLabel(type) {
  return (
    {
      HANDOUT: "学习讲义",
      EXTENDED_READING: "深度阅读",
      QUIZ: "AI 练习",
      CODE_PRACTICE: "代码实验",
      VISUALIZATION: "动画实验",
      MINDMAP: "知识导图",
      LEARNING_PATH: "学习路径",
    }[type] || "AI 资源"
  );
}

function targetForResource(type) {
  if (["HANDOUT", "EXTENDED_READING"].includes(type))
    return "/learning-materials";
  if (type === "QUIZ") return "/questions";
  if (["CODE_PRACTICE", "VISUALIZATION"].includes(type)) return "/labs";
  if (["MINDMAP", "LEARNING_PATH"].includes(type)) return "/learning-plan";
  return "/resources";
}

function displayTitle(item) {
  return titleAliases.value[item.key] || item.title;
}

function isFavorite(item) {
  return favoriteKeys.value.includes(item.key);
}

function toggleFavorite(item) {
  favoriteKeys.value = isFavorite(item)
    ? favoriteKeys.value.filter((key) => key !== item.key)
    : [...favoriteKeys.value, item.key];
  persistHistoryPreferences();
}

function renameItem(item) {
  const next = window
    .prompt("修改这条历史记录的显示标题", displayTitle(item))
    ?.trim();
  if (!next) return;
  titleAliases.value = { ...titleAliases.value, [item.key]: next.slice(0, 80) };
  persistHistoryPreferences();
}

function hideItem(item) {
  if (
    !window.confirm(
      "从“我的学习”历史中隐藏这条记录？原始资源或报告不会被删除。",
    )
  )
    return;
  hiddenKeys.value = [...hiddenKeys.value, item.key];
  persistHistoryPreferences();
}

function persistHistoryPreferences() {
  localStorage.setItem(
    `${historyStoragePrefix.value}:favorites`,
    JSON.stringify(favoriteKeys.value),
  );
  localStorage.setItem(
    `${historyStoragePrefix.value}:hidden`,
    JSON.stringify(hiddenKeys.value),
  );
  localStorage.setItem(
    `${historyStoragePrefix.value}:aliases`,
    JSON.stringify(titleAliases.value),
  );
}

function restoreHistoryPreferences() {
  try {
    favoriteKeys.value = JSON.parse(
      localStorage.getItem(`${historyStoragePrefix.value}:favorites`) || "[]",
    );
    hiddenKeys.value = JSON.parse(
      localStorage.getItem(`${historyStoragePrefix.value}:hidden`) || "[]",
    );
    titleAliases.value = JSON.parse(
      localStorage.getItem(`${historyStoragePrefix.value}:aliases`) || "{}",
    );
  } catch {
    favoriteKeys.value = [];
    hiddenKeys.value = [];
    titleAliases.value = {};
  }
}

function formatDate(value) {
  if (!value) return "时间未知";
  const date =
    typeof value === "number" && value < 10_000_000_000
      ? new Date(value * 1000)
      : new Date(value);
  if (Number.isNaN(date.getTime())) return "时间未知";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(date);
}

onMounted(async () => {
  if (!authStore.isRegistered) return;
  restoreHistoryPreferences();
  await learningSession.ensureCurrentSession("个性化学习会话");
  const userId = Number(authStore.currentUserId);
  const [reports, states] = await Promise.all([
    listFixedExamReports({ silent: true }).catch(() => []),
    listLearningStateReports().catch(() => []),
    loadLibrary(userId).catch(() => []),
  ]);
  fixedReports.value = Array.isArray(reports) ? reports : [];
  stateReports.value = Array.isArray(states) ? states : [];
});
</script>

<style scoped>
.my-learning {
  display: grid;
  gap: var(--vt-space-6);
  max-width: 1240px;
}

.my-learning-header {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(300px, 0.65fr);
  align-items: stretch;
  gap: var(--vt-space-6);
  padding: clamp(1.5rem, 3vw, 2.5rem);
  overflow: hidden;
  background:
    radial-gradient(circle at 92% 12%, rgba(13, 148, 136, 0.14), transparent 36%),
    var(--vt-bg-primary);
}

.hero-copy {
  align-self: center;
}

.hero-copy h1 {
  max-width: 760px;
  margin-bottom: var(--vt-space-3);
}

.hero-copy p {
  max-width: 760px;
  margin: 0;
  line-height: 1.7;
}

.hero-side {
  display: grid;
  align-content: space-between;
  gap: var(--vt-space-4);
  padding: var(--vt-space-4);
  border: 1px solid rgba(13, 148, 136, 0.16);
  border-radius: var(--vt-radius-lg);
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(8px);
}

.hero-side .vt-btn {
  width: 100%;
}

.current-session {
  display: grid;
  gap: var(--vt-space-2);
}

.current-session p {
  margin: 0;
  color: var(--vt-text-secondary);
}

.learning-overview {
  display: grid;
  gap: var(--vt-space-4);
}

.overview-heading h2 {
  margin: var(--vt-space-1) 0 0;
  font-size: var(--vt-text-lg);
}

.learning-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--vt-space-3);
}

.learning-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--vt-space-3);
  min-height: 128px;
  padding: var(--vt-space-4);
  color: inherit;
  text-decoration: none;
}

.learning-card:hover {
  border-color: rgba(13, 148, 136, 0.4);
  transform: translateY(-2px);
}

.learning-card h3 {
  margin: 0;
  color: var(--vt-text-primary);
  font-size: var(--vt-text-base);
}

.learning-card p {
  margin: var(--vt-space-2) 0 0;
  color: var(--vt-text-secondary);
  line-height: 1.6;
}

.entry-icon {
  display: grid;
  place-items: center;
  width: 2.5rem;
  height: 2.5rem;
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
  font-size: 1.25rem;
}

.entry-arrow {
  color: var(--vt-accent-primary);
}

.learning-history {
  display: grid;
  gap: var(--vt-space-4);
  padding: var(--vt-space-5);
}
.history-heading,
.history-main,
.history-actions,
.history-pagination {
  display: flex;
  align-items: center;
  gap: var(--vt-space-3);
}
.history-heading {
  justify-content: space-between;
}
.history-heading h2 {
  margin: var(--vt-space-1) 0 0;
  font-size: var(--vt-text-lg);
}
.history-heading > span,
.history-main small {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}
.history-filters {
  display: grid;
  grid-template-columns: 190px minmax(240px, 1fr) auto;
  gap: var(--vt-space-3);
  align-items: center;
}
.favorite-filter {
  display: flex;
  align-items: center;
  gap: var(--vt-space-2);
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
}
.history-list {
  display: grid;
}
.history-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--vt-space-3);
  border-top: 1px solid var(--vt-border-light);
}
.history-main {
  padding: var(--vt-space-3) 0;
  color: inherit;
  text-decoration: none;
}
.history-main > span {
  flex: 0 0 5rem;
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}
.history-main div {
  min-width: 0;
}
.history-main strong,
.history-main small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.history-actions button {
  border: 0;
  padding: 0.25rem;
  background: transparent;
  color: var(--vt-text-secondary);
  cursor: pointer;
}
.history-pagination {
  justify-content: center;
}
.history-pagination span,
.history-empty {
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
}

@media (max-width: 720px) {
  .my-learning-header {
    grid-template-columns: 1fr;
  }

  .learning-grid {
    grid-template-columns: 1fr;
  }

  .history-filters {
    grid-template-columns: 1fr;
  }
  .history-main > span {
    flex-basis: 4rem;
  }
}

@media (min-width: 721px) and (max-width: 1020px) {
  .my-learning-header {
    grid-template-columns: 1fr;
  }

  .learning-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
