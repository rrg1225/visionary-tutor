<template>
  <section class="resource-center vt-container vt-section">
    <header class="resource-center-header">
      <div>
        <span class="vt-eyebrow">AI 资源中心</span>
        <h1 class="vt-title">从学习任务进入资源，不在一个页面堆满所有功能</h1>
        <p class="vt-text-muted">
          这里负责导航、数量、最近内容和生成任务。阅读、做题、实验、规划与成果导出分别进入独立页面。
        </p>
      </div>
      <RouterLink class="vt-btn vt-btn-primary" :to="primaryCreateTarget">
        创建新资源
      </RouterLink>
    </header>

    <div v-if="!authStore.isRegistered" class="vt-card login-gate">
      <h2>登录后使用 AI 资源中心</h2>
      <p class="vt-text-muted">
        注册后可保存资源、生成任务、最近进度与后续学习报告。
      </p>
      <RouterLink
        class="vt-btn vt-btn-primary"
        :to="{
          path: '/auth',
          query: { mode: 'register', redirect: '/resources' },
        }"
      >
        登录 / 注册
      </RouterLink>
    </div>

    <template v-else>
      <section class="continue-card vt-card">
        <div>
          <span class="vt-eyebrow">继续上次学习</span>
          <h2>
            {{ learningSession.currentSession?.topic || "还没有学习会话" }}
          </h2>
          <p>{{ continueDescription }}</p>
        </div>
        <RouterLink class="vt-btn vt-btn-outline" :to="continueTarget">
          继续学习
        </RouterLink>
      </section>

      <section class="entry-section">
        <header class="section-heading">
          <div>
            <span class="vt-eyebrow">功能入口</span>
            <h2>按你现在要完成的任务选择</h2>
          </div>
          <span>共 {{ personalResources.length }} 项个人资源</span>
        </header>

        <div class="entry-grid">
          <article
            v-for="entry in resourceEntries"
            :key="entry.id"
            class="entry-card vt-card"
          >
            <header>
              <span class="entry-icon" aria-hidden="true">{{
                entry.icon
              }}</span>
              <strong>{{ countFor(entry.types) }}</strong>
            </header>
            <h3>{{ entry.title }}</h3>
            <p>{{ entry.description }}</p>
            <div class="entry-actions">
              <RouterLink
                class="vt-btn vt-btn-outline vt-btn-sm"
                :to="entry.to"
              >
                打开
              </RouterLink>
              <RouterLink
                v-if="entry.createTo"
                class="vt-btn vt-btn-ghost vt-btn-sm"
                :to="entry.createTo"
              >
                创建
              </RouterLink>
            </div>
          </article>
        </div>
      </section>

      <section class="activity-grid">
        <article class="vt-card recent-panel">
          <header class="panel-heading">
            <div>
              <span class="vt-eyebrow">最近内容</span>
              <h2>最近生成或继续查看</h2>
            </div>
            <span>{{ recentResources.length }} 项</span>
          </header>
          <div class="history-filters" aria-label="筛选资源历史">
            <select
              v-model="historyCategory"
              class="vt-input"
              aria-label="资源历史类型"
            >
              <option value="">全部历史</option>
              <option value="practice">题目与练习历史</option>
              <option value="planning">学习导图与路径历史</option>
              <option value="materials">学习资料历史</option>
              <option value="labs">代码与动画实验历史</option>
            </select>
            <input
              v-model.trim="historyQuery"
              class="vt-input"
              type="search"
              placeholder="搜索标题或学习主题"
              aria-label="搜索资源历史"
            />
          </div>
          <div v-if="recentResources.length" class="recent-list">
            <RouterLink
              v-for="item in recentResources"
              :key="item.id || `${item.artifactType}-${item.runId}`"
              :to="targetForType(item.artifactType)"
              class="recent-row"
            >
              <span>{{ item.type || item.artifactType }}</span>
              <div>
                <strong>{{ item.title }}</strong>
                <small>{{ item.sessionTopic || "个性化学习资源" }}</small>
              </div>
              <span aria-hidden="true">→</span>
            </RouterLink>
          </div>
          <p v-else class="empty-copy">
            完成一次资源生成后，最近内容会出现在这里。
          </p>
        </article>

        <article class="vt-card task-panel">
          <header class="panel-heading">
            <div>
              <span class="vt-eyebrow">最近生成任务</span>
              <h2>{{ taskHeadline }}</h2>
            </div>
            <span>{{ learningSession.resourceGenerationProgress }}%</span>
          </header>
          <div class="task-progress" aria-hidden="true">
            <span
              :style="{
                width: `${learningSession.resourceGenerationProgress}%`,
              }"
            ></span>
          </div>
          <p>{{ taskDescription }}</p>
          <div class="task-actions">
            <RouterLink
              class="vt-btn vt-btn-outline vt-btn-sm"
              to="/agent-trace"
            >
              查看任务详情
            </RouterLink>
            <button
              v-if="learningSession.resourceGenerationRetryable"
              type="button"
              class="vt-btn vt-btn-primary vt-btn-sm"
              @click="retryTask"
            >
              重试任务
            </button>
          </div>
        </article>
      </section>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { useResourceLibrary } from "../composables/useResourceLibrary";
import { useAuthStore } from "../stores/authStore";
import { useLearningSessionStore } from "../stores/learningSession";

const authStore = useAuthStore();
const learningSession = useLearningSessionStore();
const { libraryResources, loadLibrary } = useResourceLibrary();
const historyCategory = ref("");
const historyQuery = ref("");

const resourceEntries = [
  {
    id: "materials",
    icon: "📖",
    title: "我的学习资料",
    description: "个性化讲义与深度阅读。",
    types: ["HANDOUT", "EXTENDED_READING"],
    to: "/learning-materials",
    createTo: "/learning-materials",
  },
  {
    id: "practice",
    icon: "📝",
    title: "题库与练习",
    description: "综合题卷、AI 专项题与错题本。",
    types: ["QUIZ"],
    to: "/questions",
    createTo: "/questions/personalized",
  },
  {
    id: "labs",
    icon: "🧪",
    title: "互动实验",
    description: "动画实验与代码实验。",
    types: ["CODE_PRACTICE", "VISUALIZATION"],
    to: "/labs",
    createTo: "/labs",
  },
  {
    id: "sandbox",
    icon: "⌨️",
    title: "代码沙箱",
    description: "独立编写、运行和测试 Python，并保存运行历史。",
    types: [],
    to: "/code-sandbox",
    createTo: "/code-sandbox",
  },
  {
    id: "planning",
    icon: "🧭",
    title: "学习规划",
    description: "知识导图与学习路径。",
    types: ["MINDMAP", "LEARNING_PATH"],
    to: "/learning-plan",
    createTo: "/learning-plan",
  },
  {
    id: "outcomes",
    icon: "🏆",
    title: "学习成果",
    description: "组合、预览并导出学习成果。",
    types: [],
    to: "/learning-outcomes",
    createTo: null,
  },
  {
    id: "audit",
    icon: "🔎",
    title: "生成任务详情",
    description: "查看 Agent 协作、返修和发布审计。",
    types: [],
    to: "/agent-trace",
    createTo: null,
  },
];

const personalResources = computed(() =>
  libraryResources.value.filter(
    (item) => !item.isShowcase && item.artifactType !== "VIDEO_SCRIPT",
  ),
);
const recentResources = computed(() =>
  [...personalResources.value]
    .filter((item) => {
      const categoryTypes = {
        practice: ["QUIZ"],
        planning: ["MINDMAP", "LEARNING_PATH"],
        materials: ["HANDOUT", "EXTENDED_READING"],
        labs: ["CODE_PRACTICE", "VISUALIZATION"],
      };
      if (
        historyCategory.value &&
        !categoryTypes[historyCategory.value]?.includes(item.artifactType)
      )
        return false;
      const query = historyQuery.value.toLowerCase();
      return (
        !query ||
        `${item.title || ""} ${item.sessionTopic || ""} ${item.artifactType || ""}`
          .toLowerCase()
          .includes(query)
      );
    })
    .sort((a, b) => (b.id || 0) - (a.id || 0))
    .slice(0, 20),
);
const primaryCreateTarget = computed(() =>
  authStore.isRegistered
    ? "/learning-materials"
    : "/auth?mode=register&redirect=/resources",
);
const continueTarget = computed(() => {
  const latest = recentResources.value[0];
  return latest ? targetForType(latest.artifactType) : "/learn";
});
const continueDescription = computed(() => {
  if (recentResources.value.length)
    return `最近内容：${recentResources.value[0].title}`;
  return "先向 AI 老师提问，或从下方选择一种学习任务。";
});

const taskHeadline = computed(() => {
  if (learningSession.isGeneratingResources) return "资源正在后台生成";
  if (learningSession.resourceGenerationRetryable) return "上次任务需要重试";
  if (learningSession.lastResourceRunId) return "上次生成已完成";
  return "暂无生成任务";
});
const taskDescription = computed(
  () =>
    learningSession.resourceGenerationStatus ||
    (learningSession.lastResourceRunId
      ? `任务 ${learningSession.lastResourceRunId} 已完成，可进入对应功能页查看。`
      : "创建资源后，可在这里查看进度；切换页面不会丢失后台任务。"),
);

function countFor(types) {
  if (!types.length) return "—";
  return personalResources.value.filter((item) =>
    types.includes(item.artifactType),
  ).length;
}

function targetForType(type) {
  if (["HANDOUT", "EXTENDED_READING"].includes(type))
    return "/learning-materials";
  if (type === "QUIZ") return "/questions";
  if (["CODE_PRACTICE", "VISUALIZATION"].includes(type)) return "/labs";
  if (["MINDMAP", "LEARNING_PATH"].includes(type)) return "/learning-plan";
  return "/resources";
}

async function retryTask() {
  const ok = await learningSession.retryResourceGeneration();
  if (ok) await loadLibrary(Number(authStore.currentUserId));
}

onMounted(async () => {
  if (!authStore.isRegistered) return;
  await learningSession.ensureCurrentSession("个性化资源中心");
  await Promise.all([
    learningSession.hydrateGeneratedResources(),
    loadLibrary(Number(authStore.currentUserId)),
  ]);
  void learningSession.resumeResourceGeneration().then((completed) => {
    if (completed) return loadLibrary(Number(authStore.currentUserId));
    return null;
  });
});
</script>

<style scoped>
.resource-center,
.entry-section {
  display: grid;
  gap: var(--vt-space-5);
}

.resource-center {
  max-width: 1120px;
}

.resource-center-header,
.continue-card,
.section-heading,
.panel-heading,
.entry-card header,
.entry-actions,
.task-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
}

.resource-center-header p {
  max-width: 760px;
  line-height: 1.7;
}

.login-gate,
.continue-card,
.recent-panel,
.task-panel {
  padding: var(--vt-space-5);
}

.login-gate {
  display: grid;
  justify-items: start;
  gap: var(--vt-space-3);
}

.continue-card h2,
.section-heading h2,
.panel-heading h2,
.entry-card h3 {
  margin: 0;
  color: var(--vt-text-primary);
}

.continue-card p,
.entry-card p,
.task-panel p {
  margin: var(--vt-space-2) 0 0;
  color: var(--vt-text-secondary);
  line-height: 1.6;
}

.section-heading h2,
.panel-heading h2 {
  font-size: var(--vt-text-lg);
}

.section-heading > span,
.panel-heading > span {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.entry-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--vt-space-4);
}

.entry-card {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-4);
}

.entry-card header strong {
  color: var(--vt-accent-teal-dark);
  font-size: 1.4rem;
}

.entry-icon {
  font-size: 1.5rem;
}

.entry-actions,
.task-actions {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.activity-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(280px, 0.75fr);
  gap: var(--vt-space-4);
}

.recent-list {
  display: grid;
  margin-top: var(--vt-space-3);
}

.history-filters {
  display: grid;
  grid-template-columns: minmax(180px, 0.8fr) minmax(220px, 1.2fr);
  gap: 0.65rem;
  margin-top: var(--vt-space-3);
}

.recent-row {
  display: grid;
  grid-template-columns: 4.5rem minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--vt-space-3);
  padding: var(--vt-space-3) 0;
  border-top: 1px solid var(--vt-border-light);
  color: inherit;
  text-decoration: none;
}

.recent-row > span:first-child,
.recent-row small {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.recent-row strong,
.recent-row small {
  display: block;
}

.task-progress {
  height: 8px;
  margin-top: var(--vt-space-4);
  overflow: hidden;
  border-radius: 999px;
  background: var(--vt-bg-secondary);
}

.task-progress span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--vt-accent-teal);
  transition: width 180ms ease;
}

.empty-copy {
  color: var(--vt-text-secondary);
}

@media (max-width: 900px) {
  .entry-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .activity-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .history-filters {
    grid-template-columns: 1fr;
  }
  .resource-center-header,
  .continue-card,
  .section-heading {
    align-items: stretch;
    flex-direction: column;
  }

  .entry-grid {
    grid-template-columns: 1fr;
  }
}
</style>
