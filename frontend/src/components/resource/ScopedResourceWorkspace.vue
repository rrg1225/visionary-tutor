<template>
  <section class="scoped-workspace vt-container vt-section">
    <header class="workspace-header">
      <div>
        <span class="vt-eyebrow">{{ eyebrow }}</span>
        <h1 class="vt-title">{{ title }}</h1>
        <p class="vt-text-muted">{{ description }}</p>
      </div>
      <RouterLink class="vt-btn vt-btn-ghost vt-btn-sm" to="/resources">
        <span>返回 AI 资源中心</span>
      </RouterLink>
      <button
        type="button"
        class="vt-btn vt-btn-ghost vt-btn-sm"
        :aria-pressed="advancedMode"
        @click="toggleAdvancedMode"
      >
        {{ advancedMode ? "退出高级模式" : "演示 / 高级模式" }}
      </button>
    </header>

    <div v-if="!authStore.isRegistered" class="vt-card login-gate">
      <h2>登录后创建并保存学习资源</h2>
      <p class="vt-text-muted">正式账号会保存生成进度、学习记录和后续报告。</p>
      <RouterLink
        class="vt-btn vt-btn-primary"
        :to="{
          path: '/auth',
          query: { mode: 'register', redirect: route.fullPath },
        }"
      >
        登录 / 注册
      </RouterLink>
    </div>

    <template v-else>
      <section class="generation-card vt-card" aria-label="创建新资源">
        <header>
          <div>
            <span class="vt-eyebrow">创建新资源</span>
            <h2>只生成当前任务需要的内容</h2>
          </div>
          <span class="scope-note">本页不会生成其他资源类型</span>
        </header>

        <label class="vt-label" :for="`${workspaceId}-topic`">学习主题</label>
        <textarea
          :id="`${workspaceId}-topic`"
          v-model="topicInput"
          class="vt-input topic-input"
          rows="3"
          :placeholder="topicPlaceholder"
          :disabled="learningSession.isGeneratingResources"
        />

        <fieldset v-if="independentTypeActions" class="type-picker">
          <legend>选择要单独生成的内容</legend>
          <article
            v-for="option in typeOptions"
            :key="option.type"
            class="type-option independent"
          >
            <span class="type-emoji" aria-hidden="true">{{
              option.emoji
            }}</span>
            <span class="type-copy">
              <strong>{{ option.label }}</strong>
              <small>{{ option.hint }}</small>
            </span>
            <button
              type="button"
              class="vt-btn vt-btn-primary vt-btn-sm"
              :disabled="
                !topicInput.trim() || learningSession.isGeneratingResources
              "
              @click="createResource([option.type])"
            >
              生成{{ option.label }}
            </button>
          </article>
        </fieldset>

        <fieldset v-else class="type-picker">
          <legend>选择本次任务</legend>
          <label
            v-for="option in typeOptions"
            :key="option.type"
            class="type-option"
            :class="{ selected: isTypeSelected(option.type) }"
          >
            <input
              :type="allowMultiSelect ? 'checkbox' : 'radio'"
              :name="`${workspaceId}-type`"
              :value="option.type"
              :checked="isTypeSelected(option.type)"
              :disabled="learningSession.isGeneratingResources"
              @change="toggleType(option.type)"
            />
            <span class="type-emoji" aria-hidden="true">{{
              option.emoji
            }}</span>
            <span>
              <strong>{{ option.label }}</strong>
              <small>{{ option.hint }}</small>
            </span>
          </label>
        </fieldset>

        <div v-if="independentTypeActions" class="generation-actions">
          <p v-if="!topicInput.trim()" class="form-hint" role="status">
            请先填写本次要学习或练习的主题。
          </p>
          <p v-else class="form-hint">
            每个按钮只生成对应内容，不会联动其他资源。
          </p>
          <button
            v-if="allowMultiSelect && typeOptions.length > 1"
            type="button"
            class="vt-btn vt-btn-outline"
            :disabled="
              !topicInput.trim() || learningSession.isGeneratingResources
            "
            @click="createResource(typeOptions.map((option) => option.type))"
          >
            明确同时生成{{
              typeOptions.map((option) => option.label).join(" + ")
            }}
          </button>
        </div>

        <div v-else class="generation-actions">
          <p v-if="generationHint" class="form-hint" role="status">
            {{ generationHint }}
          </p>
          <span v-else></span>
          <button
            type="button"
            class="vt-btn vt-btn-primary"
            :disabled="!canGenerate || learningSession.isGeneratingResources"
            @click="createResource()"
          >
            {{
              learningSession.isGeneratingResources
                ? "生成中…"
                : `生成${selectedLabels || "资源"}`
            }}
          </button>
        </div>
      </section>

      <ResourceGenerationStatus
        :active="learningSession.isGeneratingResources"
        :progress="learningSession.resourceGenerationProgress"
        :message="learningSession.resourceGenerationStatus"
        :eta-seconds="learningSession.resourceGenerationEtaSeconds"
        :retryable="learningSession.resourceGenerationRetryable"
        @cancel="learningSession.cancelResourceGeneration()"
        @retry="retryGeneration"
      />

      <div
        v-if="resourceVisibilityNotice"
        class="resource-notice vt-card"
        role="alert"
      >
        <div>
          <strong>生成结果暂未出现在资源列表</strong>
          <p>{{ resourceVisibilityNotice }}</p>
        </div>
        <button
          type="button"
          class="vt-btn vt-btn-outline vt-btn-sm"
          :disabled="loading"
          @click="retryLibrarySync"
        >
          {{ loading ? "同步中…" : "重新同步" }}
        </button>
      </div>

      <div v-else-if="loadError" class="resource-notice vt-card" role="alert">
        <div>
          <strong>个人资源没有完全同步</strong>
          <p>{{ loadError }}</p>
        </div>
        <button
          type="button"
          class="vt-btn vt-btn-outline vt-btn-sm"
          :disabled="loading"
          @click="retryLibrarySync"
        >
          {{ loading ? "同步中…" : "重试" }}
        </button>
      </div>

      <LearningStateAssist
        v-if="learningStateAssist"
        context-type="INTERACTIVE_LAB"
        :context-key="workspaceId"
        :context-title="title"
      />

      <div
        class="resource-layout"
        :class="{ 'history-collapsed': !historyOpen }"
      >
        <aside v-if="historyEnabled" class="history-panel vt-card">
          <button
            type="button"
            class="history-toggle"
            :aria-expanded="historyOpen"
            @click="historyOpen = !historyOpen"
          >
            <span>{{ historyOpen ? "收起历史" : "历史" }}</span>
            <span aria-hidden="true">{{ historyOpen ? "‹" : "›" }}</span>
          </button>
          <template v-if="historyOpen">
            <label class="vt-label" :for="`${workspaceId}-history-search`"
              >搜索历史</label
            >
            <input
              :id="`${workspaceId}-history-search`"
              v-model.trim="historyQuery"
              class="vt-input"
              type="search"
              placeholder="搜索标题、主题或类型"
            />
            <select
              v-model="historyType"
              class="vt-input"
              aria-label="按实验类型筛选"
            >
              <option value="">全部类型</option>
              <option
                v-for="option in typeOptions"
                :key="option.type"
                :value="option.type"
              >
                {{ option.label }}
              </option>
            </select>
            <ul class="history-list">
              <li
                v-for="item in filteredHistory"
                :key="`history-${item.key}`"
                :class="{ active: selectedHistoryKey === item.key }"
              >
                <button
                  type="button"
                  class="history-open"
                  @click="openHistoryItem(item)"
                >
                  <strong>{{ historyTitle(item) }}</strong>
                  <small>{{ historyTypeLabel(item) }}</small>
                </button>
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
                    title="重命名"
                    @click="renameHistoryItem(item)"
                  >
                    ✎
                  </button>
                  <button
                    type="button"
                    title="从历史中移除"
                    @click="hideHistoryItem(item)"
                  >
                    ×
                  </button>
                </div>
              </li>
            </ul>
            <p
              v-if="!filteredHistory.length"
              class="vt-text-muted history-empty"
            >
              没有匹配的历史记录
            </p>
          </template>
        </aside>

        <section class="resource-section">
          <header class="section-heading">
            <div>
              <span class="vt-eyebrow">当前打开</span>
              <h2>
                {{
                  selectedHistory
                    ? historyTitle(selectedHistory)
                    : resourceSectionTitle
                }}
              </h2>
            </div>
            <span>{{
              selectedHistory
                ? `${visibleResources.length} 项`
                : historyCountLabel
            }}</span>
          </header>

          <div v-if="loading" class="vt-card loading-state">正在同步资源…</div>
          <div v-else-if="visibleResources.length" class="resource-grid">
            <ResourceCardShell
              v-for="item in visibleResources"
              :id="`resource-${resourceKey(item)}`"
              :key="item.id || `${item.artifactType}-${item.runId}`"
              :artifact-type="item.artifactType"
              :variant="item.isShowcase ? 'default' : 'fresh'"
            >
              <ResourceCardBody
                :item="item"
                :user-id="Number(authStore.currentUserId)"
                :learning-session-id="
                  item.learningSessionId || learningSession.currentSessionId
                "
                :weak-points-snapshot="weakPointsSnapshot"
                :show-governance-trace="advancedMode"
                @quiz-submitted="refreshAfterActivity"
                @resource-updated="refreshAfterActivity"
              />
            </ResourceCardShell>
          </div>
          <div v-else class="vt-card empty-state">
            <strong>{{
              selectedHistoryKey ? emptyTitle : "选择一条历史记录后再查看内容"
            }}</strong>
            <p>
              {{
                selectedHistoryKey
                  ? emptyDescription
                  : "历史资源默认不会全部展开。请从左侧历史列表打开一次生成记录，或在上方创建新资源。"
              }}
            </p>
          </div>
        </section>
      </div>

      <aside v-if="advancedMode" class="audit-note vt-card">
        <div>
          <strong>需要查看 Agent 协作与审核轨迹？</strong>
          <p>
            生成过程的调试、答辩和内容审计已放到独立任务详情，不再挤占学习页面。
          </p>
        </div>
        <RouterLink class="vt-btn vt-btn-outline vt-btn-sm" to="/agent-trace">
          <span>查看生成任务详情</span>
        </RouterLink>
      </aside>
    </template>
  </section>
</template>

<script setup>
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink, useRoute } from "vue-router";
import { RESOURCE_TYPE_OPTIONS } from "../../constants/resourceTypes";
import { useResourceGeneration } from "../../composables/useResourceGeneration";
import { useResourceLibrary } from "../../composables/useResourceLibrary";
import { useAuthStore } from "../../stores/authStore";
import { useLearningSessionStore } from "../../stores/learningSession";
import { useUserProfileStore } from "../../stores/userProfile";
import ResourceCardBody from "../ResourceCardBody.vue";
import ResourceCardShell from "./ResourceCardShell.vue";
import ResourceGenerationStatus from "./ResourceGenerationStatus.vue";
import LearningStateAssist from "../LearningStateAssist.vue";

const props = defineProps({
  workspaceId: { type: String, required: true },
  eyebrow: { type: String, default: "个性化学习" },
  title: { type: String, required: true },
  description: { type: String, required: true },
  allowedTypes: { type: Array, required: true },
  allowMultiSelect: { type: Boolean, default: false },
  defaultSelectAll: { type: Boolean, default: false },
  independentTypeActions: { type: Boolean, default: false },
  historyEnabled: { type: Boolean, default: true },
  learningStateAssist: { type: Boolean, default: false },
  typeLabels: { type: Object, default: () => ({}) },
  sessionTopic: { type: String, required: true },
  topicPlaceholder: {
    type: String,
    default: "例如：CNN 中 padding 与输出尺寸的关系",
  },
  resourceSectionTitle: { type: String, default: "我的资源" },
  emptyTitle: { type: String, default: "还没有相关资源" },
  emptyDescription: { type: String, default: "填写主题后即可创建第一项内容。" },
});

const route = useRoute();
const authStore = useAuthStore();
const userProfile = useUserProfileStore();
const learningSession = useLearningSessionStore();
const topicInput = ref("");
const selectedType = ref(props.allowedTypes[0] || "");
const selectedTypes = ref(
  props.defaultSelectAll
    ? [...props.allowedTypes]
    : [props.allowedTypes[0]].filter(Boolean),
);
const advancedMode = ref(localStorage.getItem("vt_advanced_mode") === "true");
const historyOpen = ref(false);
const selectedHistoryKey = ref("");
const historyQuery = ref("");
const historyType = ref("");
const resourceVisibilityNotice = ref("");
const favoriteKeys = ref(
  readStoredObject(`${props.workspaceId}:favorites`, []),
);
const hiddenKeys = ref(readStoredObject(`${props.workspaceId}:hidden`, []));
const historyAliases = ref(
  readStoredObject(`${props.workspaceId}:aliases`, {}),
);

const typeOptions = computed(() =>
  props.allowedTypes
    .map((type) => RESOURCE_TYPE_OPTIONS.find((option) => option.type === type))
    .filter(Boolean)
    .map((option) => ({
      ...option,
      label: props.typeLabels[option.type] || option.label,
    })),
);
const effectiveTypes = computed(() =>
  props.allowMultiSelect
    ? selectedTypes.value
    : [selectedType.value].filter(Boolean),
);
const selectedLabels = computed(() =>
  effectiveTypes.value
    .map(
      (type) =>
        typeOptions.value.find((option) => option.type === type)?.label || type,
    )
    .join(" + "),
);
const canGenerate = computed(() =>
  Boolean(topicInput.value.trim() && effectiveTypes.value.length),
);
const generationHint = computed(() => {
  if (!topicInput.value.trim()) return "请填写本次要学习或练习的主题。";
  if (!effectiveTypes.value.length) return "请至少选择一种任务类型。";
  return "";
});

const { libraryResources, loading, loadError, loadLibrary } =
  useResourceLibrary();
const scopedResources = computed(() =>
  libraryResources.value.filter((item) =>
    props.allowedTypes.includes(item.artifactType),
  ),
);
const availableResources = computed(() =>
  scopedResources.value.filter(
    (item) => !hiddenKeys.value.includes(resourceKey(item)),
  ),
);
const historyGroups = computed(() => {
  const grouped = new Map();
  for (const resource of availableResources.value) {
    const key = historyGroupKey(resource);
    const existing = grouped.get(key);
    if (existing) {
      existing.resources.push(resource);
      existing.latestId = Math.max(existing.latestId, Number(resource.id || 0));
    } else {
      grouped.set(key, {
        key,
        runId: resource.runId || "",
        learningSessionId: resource.learningSessionId,
        sessionTopic: resource.sessionTopic || "",
        resources: [resource],
        latestId: Number(resource.id || 0),
      });
    }
  }
  return [...grouped.values()].sort(
    (left, right) =>
      Number(isFavorite(right)) - Number(isFavorite(left)) ||
      right.latestId - left.latestId,
  );
});
const personalHistoryGroups = computed(() =>
  historyGroups.value.filter(
    (item) => !item.resources.every((resource) => resource.isShowcase),
  ),
);
const showcaseHistoryGroups = computed(() =>
  historyGroups.value.filter((item) =>
    item.resources.every((resource) => resource.isShowcase),
  ),
);
const historyCountLabel = computed(() => {
  const personalCount = personalHistoryGroups.value.length;
  const showcaseCount = showcaseHistoryGroups.value.length;
  if (!showcaseCount) return `${personalCount} 次生成`;
  return `${personalCount} 次生成 · ${showcaseCount} 项示例`;
});
const selectedHistory = computed(
  () =>
    historyGroups.value.find((item) => item.key === selectedHistoryKey.value) ||
    null,
);
const visibleResources = computed(() => selectedHistory.value?.resources || []);
const filteredHistory = computed(() => {
  const keyword = historyQuery.value.toLowerCase();
  return historyGroups.value
    .filter(
      (item) =>
        !historyType.value ||
        item.resources.some(
          (resource) => resource.artifactType === historyType.value,
        ),
    )
    .filter((item) => {
      if (!keyword) return true;
      return `${historyTitle(item)} ${item.sessionTopic || ""} ${historyTypeLabel(item)}`
        .toLowerCase()
        .includes(keyword);
    });
});
const weakPointsSnapshot = computed(
  () =>
    learningSession.weakNodes.map((node) => node.name).join("、") ||
    userProfile.weakPoints.join("、"),
);

const { defaultResourceTopic, generateResources, refreshRecommendations } =
  useResourceGeneration(() => topicInput.value);

watch(
  typeOptions,
  (options) => {
    if (!options.some((option) => option.type === selectedType.value)) {
      selectedType.value = options[0]?.type || "";
    }
    selectedTypes.value = selectedTypes.value.filter((type) =>
      options.some((option) => option.type === type),
    );
    if (!selectedTypes.value.length && options[0])
      selectedTypes.value = [options[0].type];
  },
  { immediate: true },
);

async function createResource(requestedTypes = effectiveTypes.value) {
  const types = Array.isArray(requestedTypes)
    ? requestedTypes.filter(Boolean)
    : [];
  if (!topicInput.value.trim() || !types.length) return;
  resourceVisibilityNotice.value = "";
  const previousPersonalKeys = new Set(
    personalHistoryGroups.value.map((item) => item.key),
  );
  const ok = await generateResources(
    {
      topic: topicInput.value.trim(),
      resourceTypes: types,
    },
    { redirectToLibrary: false, startNewSession: true },
  );
  if (ok) {
    const newRunId = learningSession.lastResourceRunId;
    const newSessionId = Number(learningSession.currentSessionId || 0);
    await refreshAfterActivity();
    const generated = historyGroups.value.find(
      (item) =>
        !item.resources.every((resource) => resource.isShowcase) &&
        ((newRunId && item.runId === newRunId) ||
          (newSessionId && Number(item.learningSessionId) === newSessionId) ||
          !previousPersonalKeys.has(item.key)),
    );
    if (generated) {
      selectedHistoryKey.value = generated.key;
      historyOpen.value = true;
      return;
    }
    resourceVisibilityNotice.value =
      "任务已经结束，但后端没有返回可发布的个人资源。常见原因是旧版后端把未命中 RAG 的模型结果标记为 BLOCKED，或个人资源接口同步失败。";
  }
}

function toggleType(type) {
  if (!props.allowMultiSelect) {
    selectedType.value = type;
    return;
  }
  selectedTypes.value = selectedTypes.value.includes(type)
    ? selectedTypes.value.filter((item) => item !== type)
    : [...selectedTypes.value, type];
}

function isTypeSelected(type) {
  return effectiveTypes.value.includes(type);
}

function toggleAdvancedMode() {
  advancedMode.value = !advancedMode.value;
  localStorage.setItem("vt_advanced_mode", String(advancedMode.value));
}

function storageKey(suffix) {
  return `vt_resource_history_${suffix}`;
}

function readStoredObject(suffix, fallback) {
  try {
    const parsed = JSON.parse(
      localStorage.getItem(storageKey(suffix)) || "null",
    );
    return parsed ?? fallback;
  } catch {
    return fallback;
  }
}

function persistHistoryPreferences() {
  localStorage.setItem(
    storageKey(`${props.workspaceId}:favorites`),
    JSON.stringify(favoriteKeys.value),
  );
  localStorage.setItem(
    storageKey(`${props.workspaceId}:hidden`),
    JSON.stringify(hiddenKeys.value),
  );
  localStorage.setItem(
    storageKey(`${props.workspaceId}:aliases`),
    JSON.stringify(historyAliases.value),
  );
}

function resourceKey(item) {
  return String(
    item.id ??
      `${item.learningSessionId || "session"}-${item.artifactType}-${item.runId || "resource"}`,
  );
}

function historyGroupKey(item) {
  return item.runId ? `run:${item.runId}` : `resource:${resourceKey(item)}`;
}

function historyTitle(item) {
  return (
    historyAliases.value[item.key || resourceKey(item)] ||
    item.sessionTopic ||
    item.resources?.[0]?.title ||
    item.title ||
    item.sessionTopic ||
    "未命名学习资源"
  );
}

function historyTypeLabel(item) {
  const resources = item.resources || [item];
  const labels = resources.map(
    (resource) =>
      props.typeLabels[resource.artifactType] || resource.artifactType,
  );
  return [...new Set(labels)].join(" + ");
}

function isFavorite(item) {
  return favoriteKeys.value.includes(item.key || resourceKey(item));
}

function toggleFavorite(item) {
  const key = item.key || resourceKey(item);
  favoriteKeys.value = isFavorite(item)
    ? favoriteKeys.value.filter((value) => value !== key)
    : [...favoriteKeys.value, key];
  persistHistoryPreferences();
}

function renameHistoryItem(item) {
  const next = window.prompt("重命名这条历史记录", historyTitle(item))?.trim();
  if (!next) return;
  historyAliases.value = {
    ...historyAliases.value,
    [item.key || resourceKey(item)]: next.slice(0, 80),
  };
  persistHistoryPreferences();
}

function hideHistoryItem(item) {
  if (!window.confirm("从当前历史列表中移除这条记录？资源本身不会被删除。"))
    return;
  const resourceKeys = (item.resources || [item]).map(resourceKey);
  hiddenKeys.value = [...new Set([...hiddenKeys.value, ...resourceKeys])];
  if (selectedHistoryKey.value === item.key) selectedHistoryKey.value = "";
  persistHistoryPreferences();
}

function openHistoryItem(item) {
  selectedHistoryKey.value = item.key;
  window.requestAnimationFrame(() => {
    document
      .getElementById(`resource-${resourceKey(item.resources?.[0] || item)}`)
      ?.scrollIntoView({ behavior: "smooth", block: "start" });
  });
}

async function retryGeneration() {
  const ok = await learningSession.retryResourceGeneration();
  if (ok) await refreshAfterActivity();
}

async function refreshAfterActivity() {
  await learningSession.hydrateGeneratedResources();
  await Promise.all([
    loadLibrary(Number(authStore.currentUserId)),
    refreshRecommendations(),
  ]);
}

async function retryLibrarySync() {
  await refreshAfterActivity();
  const newestPersonal = personalHistoryGroups.value[0];
  if (newestPersonal) {
    selectedHistoryKey.value = newestPersonal.key;
    historyOpen.value = true;
    resourceVisibilityNotice.value = "";
  }
}

async function bootstrap() {
  if (!authStore.isRegistered) return;
  await learningSession.ensureCurrentSession(props.sessionTopic);
  if (!topicInput.value.trim()) {
    topicInput.value =
      typeof route.query.topic === "string"
        ? route.query.topic.trim()
        : defaultResourceTopic.value || "";
  }
  await Promise.all([
    learningSession.hydrateGeneratedResources(),
    loadLibrary(Number(authStore.currentUserId)),
  ]);
  const newestPersonal = personalHistoryGroups.value[0];
  if (newestPersonal) {
    selectedHistoryKey.value = newestPersonal.key;
    historyOpen.value = true;
  }
  void learningSession.resumeResourceGeneration().then((completed) => {
    if (completed) return retryLibrarySync();
    return null;
  });
}

onMounted(bootstrap);
</script>

<style scoped>
.scoped-workspace,
.resource-section {
  display: grid;
  gap: var(--vt-space-5);
}

.scoped-workspace {
  width: min(100%, 1480px);
  max-width: none;
}

.resource-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
  border-color: color-mix(
    in srgb,
    var(--vt-accent-orange) 42%,
    var(--vt-border-light)
  );
  background: color-mix(in srgb, var(--vt-accent-orange) 8%, var(--vt-surface));
}

.resource-notice p {
  margin: var(--vt-space-1) 0 0;
  color: var(--vt-text-muted);
}

.workspace-header,
.generation-card header,
.generation-actions,
.section-heading,
.audit-note {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-4);
}

.workspace-header p {
  max-width: 760px;
  line-height: 1.7;
}

.generation-card,
.login-gate,
.empty-state,
.loading-state,
.audit-note {
  padding: var(--vt-space-5);
}

.generation-card {
  display: grid;
  gap: var(--vt-space-4);
}

.generation-card h2,
.section-heading h2,
.login-gate h2,
.audit-note p {
  margin: 0;
}

.generation-card h2,
.section-heading h2 {
  font-size: var(--vt-text-lg);
  color: var(--vt-text-primary);
}

.scope-note,
.section-heading > span,
.form-hint {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.topic-input {
  width: 100%;
  resize: vertical;
  line-height: 1.6;
}

.type-picker {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: var(--vt-space-3);
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}

.type-picker legend {
  grid-column: 1 / -1;
  margin-bottom: var(--vt-space-2);
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
  font-weight: var(--vt-font-semibold);
}

.type-option {
  display: grid;
  grid-template-columns: auto auto minmax(0, 1fr);
  align-items: center;
  gap: var(--vt-space-3);
  padding: var(--vt-space-3);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
  cursor: pointer;
}

.type-option.independent {
  grid-template-columns: auto minmax(0, 1fr) auto;
  cursor: default;
}

.type-copy {
  min-width: 0;
}

.type-option.selected {
  border-color: rgba(13, 148, 136, 0.55);
  background: rgba(13, 148, 136, 0.08);
}

.type-option strong,
.type-option small {
  display: block;
}

.type-option small {
  margin-top: 3px;
  color: var(--vt-text-tertiary);
}

.type-emoji {
  font-size: 1.45rem;
}

.resource-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--vt-space-4);
}

.resource-layout {
  display: grid;
  grid-template-columns: minmax(230px, 286px) minmax(0, 1fr);
  align-items: start;
  gap: var(--vt-space-5);
}

.resource-layout.history-collapsed {
  grid-template-columns: 58px minmax(0, 1fr);
}

.history-panel {
  position: sticky;
  top: calc(var(--vt-header-height, 64px) + var(--vt-space-3));
  display: grid;
  gap: var(--vt-space-3);
  max-height: calc(100vh - 100px);
  padding: var(--vt-space-3);
  overflow: auto;
}

.history-toggle,
.history-open,
.history-actions button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.history-toggle {
  display: flex;
  justify-content: space-between;
  width: 100%;
  padding: var(--vt-space-2);
  font-weight: var(--vt-font-semibold);
}

.history-list {
  display: grid;
  gap: var(--vt-space-2);
  margin: 0;
  padding: 0;
  list-style: none;
}

.history-list li {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--vt-space-2);
  padding: var(--vt-space-2);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
}

.history-list li.active {
  border-color: rgba(13, 148, 136, 0.55);
  background: rgba(13, 148, 136, 0.09);
}

.history-open {
  min-width: 0;
  text-align: left;
}

.history-open strong,
.history-open small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-open small,
.history-empty {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.history-actions {
  display: flex;
  align-items: start;
}

.history-actions button {
  padding: 2px 4px;
}

.empty-state {
  display: grid;
  justify-items: start;
  gap: var(--vt-space-2);
}

.empty-state p,
.audit-note p {
  color: var(--vt-text-secondary);
  line-height: 1.6;
}

.audit-note {
  border-style: dashed;
}

@media (max-width: 760px) {
  .workspace-header,
  .generation-card header,
  .generation-actions,
  .section-heading,
  .audit-note {
    align-items: stretch;
    flex-direction: column;
  }

  .resource-grid {
    grid-template-columns: 1fr;
  }

  .resource-layout,
  .resource-layout.history-collapsed {
    grid-template-columns: 1fr;
  }

  .history-panel {
    position: static;
    max-height: min(55vh, 520px);
  }

  .type-option.independent {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .type-option.independent .vt-btn {
    grid-column: 1 / -1;
    width: 100%;
  }
}
</style>
