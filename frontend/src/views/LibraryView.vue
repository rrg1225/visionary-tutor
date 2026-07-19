<template>
  <main class="content-center">
    <header class="center-header vt-card">
      <div>
        <span class="vt-eyebrow">内容中心</span>
        <h1>从开放原典、论文和工程文章中学习</h1>
        <p>
          系统内容与社区投稿分区展示。每条系统内容都标注作者、出处、许可、版本与人工复核状态，
          不再用一批同质化“书籍卡片”冒充知识库。
        </p>
      </div>
      <button
        v-if="authStore.isRegistered"
        class="vt-btn vt-btn-primary"
        type="button"
        @click="openSubmission"
      >
        提交社区内容
      </button>
    </header>

    <nav class="center-tabs vt-card" aria-label="内容中心分区">
      <button
        v-for="tab in tabs"
        :key="tab.id"
        type="button"
        :class="{ active: activeTab === tab.id }"
        @click="activeTab = tab.id"
      >
        {{ tab.label }}
        <span>{{ tab.count }}</span>
      </button>
      <RouterLink v-if="isAdmin" class="review-link" to="/admin/ugc-review"
        >
审核队列
</RouterLink
      >
    </nav>

    <section v-if="activeTab === 'system'" class="system-section">
      <div class="filter-row vt-card">
        <label>
          内容类型
          <select v-model="kindFilter">
            <option value="ALL">全部类型</option>
            <option value="BOOK">章节式专题书</option>
            <option value="ARTICLE">工程文章</option>
            <option value="PAPER">论文导读</option>
            <option value="REVIEW">综述导读</option>
          </select>
        </label>
        <label>
          搜索
          <input
            v-model.trim="search"
            type="search"
            placeholder="标题、学科、作者或来源"
          />
        </label>
        <p>
          目录版本：{{ catalogVersion || "加载中" }} ·
          正式发布前仍需团队最终人工复核
        </p>
      </div>
      <div v-if="loading" class="empty vt-card">正在校验并加载系统内容…</div>
      <div v-else-if="loadError" class="empty vt-card">{{ loadError }}</div>
      <template v-else>
        <div v-if="!groupedSystemItems.length" class="empty vt-card">
          没有匹配的系统内容，换个关键词或类型试试。
        </div>
        <section
          v-for="group in groupedSystemItems"
          :key="group.kind"
          class="kind-group"
        >
          <header class="kind-group-header">
            <h2>
              {{ kindLabel(group.kind) }} <span>{{ group.items.length }}</span>
            </h2>
            <p>{{ kindDescription(group.kind) }}</p>
          </header>
          <div class="content-grid">
            <article
              v-for="content in group.items"
              :key="content.slug"
              class="content-card vt-card"
            >
              <div class="card-topline">
                <span :class="['kind', content.kind.toLowerCase()]">{{
                  kindLabel(content.kind)
                }}</span>
                <span>{{ content.subject }} · {{ content.difficulty }}</span>
              </div>
              <h3>{{ content.title }}</h3>
              <p class="subtitle">{{ content.subtitle }}</p>
              <p>{{ content.description }}</p>
              <dl>
                <div>
                  <dt>作者/整理</dt>
                  <dd>{{ content.authorLabel }}</dd>
                </div>
                <div>
                  <dt>出处</dt>
                  <dd>
                    {{ content.venue }} {{ content.publicationYear || "" }}
                  </dd>
                </div>
                <div>
                  <dt>结构</dt>
                  <dd>
                    {{ content.sectionCount }} 章/节 · 约
                    {{ content.estimatedMinutes }} 分钟
                  </dd>
                </div>
                <div>
                  <dt>许可</dt>
                  <dd>{{ content.licenseName }}</dd>
                </div>
              </dl>
              <div class="card-actions">
                <RouterLink
                  class="vt-btn vt-btn-primary vt-btn-sm"
                  :to="`/knowledge-content/${content.slug}`"
                >
                  开始深度阅读
                </RouterLink>
                <a
                  class="vt-btn vt-btn-ghost vt-btn-sm"
                  :href="content.sources[0]?.url"
                  target="_blank"
                  rel="noreferrer"
                >
                  查看原始来源
                </a>
              </div>
            </article>
          </div>
        </section>
      </template>
    </section>

    <section v-else-if="activeTab === 'community'" class="content-grid">
      <div v-if="communityLoading" class="empty vt-card">正在加载社区内容…</div>
      <div v-else-if="!communityItems.length" class="empty vt-card">
        目前没有已通过来源和权利审核的社区内容。
      </div>
      <article
        v-for="book in communityItems"
        :key="book.id"
        class="content-card vt-card"
      >
        <div class="card-topline">
          <span class="kind community">社区内容</span
          ><span>{{ book.subjectTag || "综合" }}</span>
        </div>
        <h2>{{ book.title }}</h2>
        <p>{{ book.description }}</p>
        <small
          >{{ sourceTypeLabel(book.sourceType) }} ·
          {{ book.viewCount || 0 }} 次阅读</small
        >
        <RouterLink
          class="vt-btn vt-btn-outline vt-btn-sm"
          :to="`/library/${book.id}`"
          >
阅读全文
</RouterLink
        >
      </article>
    </section>

    <section v-else-if="activeTab === 'learning'" class="content-grid">
      <div v-if="!learningItems.length" class="empty vt-card">
        还没有系统内容阅读记录。先从“系统内容”选择一本专题书或一篇导读。
      </div>
      <article
        v-for="entry in learningItems"
        :key="entry.slug"
        class="content-card vt-card"
      >
        <div class="card-topline">
          <span class="kind">{{ kindLabel(entry.kind) }}</span
          ><span>{{ entry.progress }}%</span>
        </div>
        <h2>{{ entry.title }}</h2>
        <progress :value="entry.progress" max="100"></progress>
        <p>
          {{ entry.selfTestCompleted ? "已完成整篇自测" : "自测尚未完成" }} ·
          {{ formatTime(entry.updatedAt) }}
        </p>
        <RouterLink
          class="vt-btn vt-btn-primary vt-btn-sm"
          :to="`/knowledge-content/${entry.slug}`"
          >
继续学习
</RouterLink
        >
      </article>
    </section>

    <section v-else class="submissions-layout">
      <form
        v-if="showSubmit"
        class="submission-form vt-card"
        @submit.prevent="handleSubmit"
      >
        <div>
          <span class="vt-eyebrow">社区投稿</span>
          <h2>提交可追溯、可授权的学习内容</h2>
        </div>
        <label
          >标题<input v-model.trim="form.title" required maxlength="255"
        /></label>
        <label
          >简介<textarea
            v-model.trim="form.description"
            rows="2"
            maxlength="2000"
          ></textarea>
        </label>
        <label
          >正文（Markdown）<textarea
            v-model="form.contentMarkdown"
            rows="12"
            minlength="300"
            required
            placeholder="至少 300 字，并用 ## 设置两个以上章节；建议包含适用对象、章节导读、重点知识、学习建议和思考题。"
          ></textarea>
        </label>
        <label
          >学科标签<input v-model.trim="form.subjectTag" maxlength="64"
        /></label>
        <label
          >来源类型
          <select v-model="form.sourceType" required>
            <option value="original">本人原创</option>
            <option value="personal_notes">基于资料整理的个人笔记</option>
            <option value="open_license">开放许可材料</option>
            <option value="authorized">已获得授权</option>
          </select>
        </label>
        <label
          >原始资料名称<input
            v-model.trim="form.sourceTitle"
            :required="form.sourceType !== 'original'"
        /></label>
        <label
          >原始链接<input
            v-model.trim="form.sourceUrl"
            type="url"
            placeholder="https://…"
        /></label>
        <label
          >许可/授权方式<input
            v-model.trim="form.licenseName"
            :required="
              ['open_license', 'authorized'].includes(form.sourceType)
            "
        /></label>
        <label
          >来源与权利说明<textarea
            v-model.trim="form.rightsStatement"
            rows="3"
            required
          ></textarea>
        </label>
        <label class="rights-check"
          ><input
            v-model="form.rightsConfirmed"
            type="checkbox"
            required
          />我确认信息真实并拥有提交、分享及供平台检索使用的权利。</label
        >
        <div class="card-actions">
          <button
            class="vt-btn vt-btn-primary"
            type="submit"
            :disabled="submitting"
          >
            {{ submitting ? "提交中…" : "提交审核" }}
          </button>
          <button
            class="vt-btn vt-btn-ghost"
            type="button"
            @click="showSubmit = false"
          >
            取消
          </button>
        </div>
        <p v-if="submitMessage" role="status">{{ submitMessage }}</p>
      </form>
      <div class="content-grid submissions-list">
        <div v-if="!myItems.length" class="empty vt-card">还没有投稿记录。</div>
        <article
          v-for="book in myItems"
          :key="book.id"
          class="content-card vt-card"
        >
          <div class="card-topline">
            <span class="kind community">我的投稿</span
            ><span :class="`review-${book.reviewStatus}`">{{
              reviewLabel(book.reviewStatus)
            }}</span>
          </div>
          <h2>{{ book.title }}</h2>
          <p>{{ book.description }}</p>
          <p class="ai-review-status">
            <strong>AI 安全初审：</strong>{{ aiReviewLabel(book.aiReviewStatus)
            }}<span v-if="book.aiReviewReason">
              · {{ book.aiReviewReason }}</span
            >
          </p>
          <p v-if="book.rejectionReason" class="rejection">
            驳回原因：{{ book.rejectionReason }}
          </p>
          <RouterLink
            v-if="book.reviewStatus === 'approved'"
            class="vt-btn vt-btn-outline vt-btn-sm"
            :to="`/library/${book.id}`"
            >
查看已发布内容
</RouterLink
          >
        </article>
      </div>
    </section>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from "vue";
import { RouterLink } from "vue-router";
import { useAuthStore } from "../stores/authStore";
import { listSystemKnowledgeContent } from "../api/knowledgeContent";
import {
  listMyTextbooks,
  listPublicTextbooks,
  submitTextbook,
} from "../api/library";
import { formatApiErrorMessage } from "../api/resources";
import api from "../api/index";

const authStore = useAuthStore();
const activeTab = ref("system");
const kindFilter = ref("ALL");
const search = ref("");
const loading = ref(true);
const communityLoading = ref(true);
const loadError = ref("");
const systemItems = ref([]);
const communityItems = ref([]);
const myItems = ref([]);
const learningItems = ref([]);
const isAdmin = ref(false);
const showSubmit = ref(false);
const submitting = ref(false);
const submitMessage = ref("");
const form = reactive({
  title: "",
  description: "",
  contentMarkdown: "",
  subjectTag: "computer-vision",
  visibility: "public",
  sourceType: "original",
  sourceTitle: "",
  sourceUrl: "",
  licenseName: "",
  rightsStatement: "",
  rightsConfirmed: false,
});

const catalogVersion = computed(
  () => systemItems.value[0]?.catalogVersion || "",
);
const filteredSystemItems = computed(() => {
  const keyword = search.value.toLowerCase();
  return systemItems.value.filter((item) => {
    if (kindFilter.value !== "ALL" && item.kind !== kindFilter.value)
      return false;
    if (!keyword) return true;
    return [
      item.title,
      item.description,
      item.subject,
      item.authorLabel,
      item.venue,
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase()
      .includes(keyword);
  });
});
const KIND_ORDER = ["BOOK", "ARTICLE", "PAPER", "REVIEW"];
const groupedSystemItems = computed(() =>
  KIND_ORDER.map((kind) => ({
    kind,
    items: filteredSystemItems.value.filter((item) => item.kind === kind),
  })).filter((group) => group.items.length),
);
const tabs = computed(() => [
  { id: "system", label: "系统内容", count: systemItems.value.length },
  { id: "community", label: "社区内容", count: communityItems.value.length },
  { id: "learning", label: "我的学习", count: learningItems.value.length },
  { id: "submissions", label: "我的投稿", count: myItems.value.length },
]);

function kindLabel(kind) {
  return (
    {
      BOOK: "章节式专题书",
      ARTICLE: "工程文章",
      PAPER: "论文导读",
      REVIEW: "综述导读",
    }[kind] || kind
  );
}
function kindDescription(kind) {
  return (
    {
      BOOK: "系统化的章节式内容，适合从零建立一个主题的完整知识框架。",
      ARTICLE: "聚焦单个工程问题的实践文章，适合解决具体训练与调参疑问。",
      PAPER: "原始论文的平台导读：给出原文链接与中文讲解，不复制整篇论文。",
      REVIEW: "领域综述的导读版本，帮助快速了解一个方向的方法脉络与对比。",
    }[kind] || ""
  );
}
function sourceTypeLabel(type) {
  return (
    {
      original: "原创",
      personal_notes: "整理笔记",
      open_license: "开放许可",
      authorized: "已获授权",
      legacy_import: "历史导入",
    }[type] || "来源待复核"
  );
}
function reviewLabel(status) {
  return (
    { approved: "已通过", rejected: "已驳回", pending: "待审核" }[status] ||
    "待审核"
  );
}
function aiReviewLabel(status) {
  return (
    {
      passed: "低风险，等待人工终审",
      blocked: "已拦截",
      manual_review: "需要人工复核",
      not_scanned: "等待扫描",
    }[status] || "需要人工复核"
  );
}
function formatTime(value) {
  return value
    ? new Intl.DateTimeFormat("zh-CN", {
        month: "numeric",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      }).format(new Date(value))
    : "";
}
function openSubmission() {
  activeTab.value = "submissions";
  showSubmit.value = true;
}

function readProgress() {
  try {
    learningItems.value = Object.values(
      JSON.parse(localStorage.getItem("vt_knowledge_progress") || "{}"),
    ).sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)));
  } catch {
    learningItems.value = [];
  }
}

async function loadAll() {
  loading.value = true;
  communityLoading.value = true;
  const [systemResult, communityResult, mineResult, adminResult] =
    await Promise.allSettled([
      listSystemKnowledgeContent({ silent: true }),
      listPublicTextbooks(),
      authStore.isRegistered ? listMyTextbooks() : Promise.resolve([]),
      authStore.isRegistered
        ? api.get("/library/admin/status", { silent: true })
        : Promise.resolve({ data: {} }),
    ]);
  if (systemResult.status === "fulfilled")
    systemItems.value = systemResult.value;
  else
    loadError.value = formatApiErrorMessage(
      systemResult.reason,
      "系统内容接口暂时不可用。",
    );
  communityItems.value =
    communityResult.status === "fulfilled" ? communityResult.value : [];
  myItems.value = mineResult.status === "fulfilled" ? mineResult.value : [];
  isAdmin.value =
    adminResult.status === "fulfilled" &&
    Boolean(adminResult.value?.data?.admin);
  loading.value = false;
  communityLoading.value = false;
}

async function handleSubmit() {
  submitting.value = true;
  submitMessage.value = "";
  try {
    await submitTextbook({ ...form });
    submitMessage.value = "投稿成功，内容将在来源、授权和质量审核通过后公开。";
    Object.assign(form, {
      title: "",
      description: "",
      contentMarkdown: "",
      sourceType: "original",
      sourceTitle: "",
      sourceUrl: "",
      licenseName: "",
      rightsStatement: "",
      rightsConfirmed: false,
    });
    myItems.value = await listMyTextbooks();
    showSubmit.value = false;
  } catch (reason) {
    submitMessage.value =
      reason?.response?.data?.message || reason?.message || "提交失败。";
  } finally {
    submitting.value = false;
  }
}

onMounted(() => {
  readProgress();
  void loadAll();
});
</script>

<style scoped>
.content-center {
  max-width: 1240px;
  margin: 0 auto;
  padding: 1.2rem 1rem 3rem;
  display: grid;
  gap: 1rem;
}
.center-header {
  padding: 1.35rem;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 1rem;
  background:
    radial-gradient(
      circle at 90% 10%,
      rgba(15, 118, 110, 0.18),
      transparent 35%
    ),
    linear-gradient(135deg, #fff, rgba(238, 242, 255, 0.9));
}
.center-header h1 {
  margin: 0.35rem 0;
}
.center-header p {
  max-width: 800px;
  line-height: 1.7;
}
.center-tabs {
  padding: 0.5rem;
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}
.center-tabs button,
.review-link {
  border: 0;
  border-radius: 9px;
  padding: 0.55rem 0.8rem;
  background: transparent;
  text-decoration: none;
  color: var(--vt-text-secondary);
  cursor: pointer;
}
.center-tabs button.active {
  background: rgba(15, 118, 110, 0.11);
  color: #0f766e;
  font-weight: 700;
}
.center-tabs button span {
  margin-left: 0.3rem;
  opacity: 0.65;
}
.review-link {
  margin-left: auto;
}
.system-section {
  display: grid;
  gap: 1rem;
}
.kind-group {
  display: grid;
  gap: 0.6rem;
}
.kind-group-header {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.6rem;
  padding: 0 0.15rem;
}
.kind-group-header h2 {
  margin: 0;
  font-size: 1.05rem;
}
.kind-group-header h2 span {
  margin-left: 0.3rem;
  font-size: 0.82rem;
  font-weight: 600;
  color: var(--vt-text-secondary);
}
.kind-group-header p {
  margin: 0;
  color: var(--vt-text-secondary);
  font-size: 0.84rem;
}
.content-card h3 {
  margin: 0.15rem 0;
  font-size: 1.08rem;
  line-height: 1.4;
}
.filter-row {
  padding: 0.9rem;
  display: grid;
  grid-template-columns: 200px minmax(220px, 1fr) auto;
  gap: 0.8rem;
  align-items: end;
}
.filter-row label {
  display: grid;
  gap: 0.3rem;
  font-size: 0.84rem;
}
.filter-row input,
.filter-row select {
  padding: 0.55rem;
  border: 1px solid rgba(148, 163, 184, 0.4);
  border-radius: 8px;
  font: inherit;
}
.filter-row p {
  margin: 0;
  color: var(--vt-text-secondary);
  font-size: 0.82rem;
}
.content-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 0.8rem;
}
.content-card {
  padding: 1.05rem;
  display: flex;
  flex-direction: column;
  gap: 0.55rem;
  min-height: 250px;
  border: 1px solid rgba(148, 163, 184, 0.22);
}
.content-card h2 {
  margin: 0.15rem 0;
  font-size: 1.08rem;
  line-height: 1.4;
}
.content-card p {
  margin: 0;
  line-height: 1.55;
}
.subtitle {
  color: #0f766e;
  font-size: 0.86rem;
}
.card-topline {
  display: flex;
  justify-content: space-between;
  gap: 0.5rem;
  color: var(--vt-text-secondary);
  font-size: 0.78rem;
}
.kind {
  padding: 0.15rem 0.5rem;
  border-radius: 999px;
  background: rgba(79, 70, 229, 0.1);
  color: #4338ca;
}
.kind.article {
  background: rgba(15, 118, 110, 0.1);
  color: #0f766e;
}
.kind.paper {
  background: rgba(217, 119, 6, 0.1);
  color: #b45309;
}
.kind.review,
.kind.community {
  background: rgba(190, 24, 93, 0.08);
  color: #be185d;
}
.content-card dl {
  display: grid;
  gap: 0.25rem;
  font-size: 0.8rem;
  color: var(--vt-text-secondary);
}
.content-card dl div {
  display: grid;
  grid-template-columns: 62px 1fr;
}
.content-card dt {
  font-weight: 700;
}
.content-card dd {
  margin: 0;
}
.card-actions {
  margin-top: auto;
  display: flex;
  flex-wrap: wrap;
  gap: 0.45rem;
  padding-top: 0.45rem;
}
.content-card progress {
  width: 100%;
}
.empty {
  padding: 2rem;
  text-align: center;
  grid-column: 1 / -1;
}
.submissions-layout {
  display: grid;
  gap: 1rem;
}
.submission-form {
  max-width: 760px;
  padding: 1.2rem;
  display: grid;
  gap: 0.75rem;
}
.submission-form label {
  display: grid;
  gap: 0.3rem;
  font-size: 0.88rem;
}
.submission-form input,
.submission-form textarea,
.submission-form select {
  padding: 0.55rem;
  border: 1px solid rgba(148, 163, 184, 0.4);
  border-radius: 8px;
  font: inherit;
}
.submission-form .rights-check {
  display: flex;
  align-items: flex-start;
}
.review-approved {
  color: #047857;
}
.review-rejected,
.rejection {
  color: #b91c1c;
}
.review-pending {
  color: #b45309;
}
.ai-review-status {
  padding: 0.55rem;
  border-radius: 0.5rem;
  background: rgba(13, 148, 136, 0.06);
  color: var(--vt-text-secondary);
  font-size: 0.78rem;
  line-height: 1.5;
}
@media (max-width: 760px) {
  .center-header {
    flex-direction: column;
  }
  .filter-row {
    grid-template-columns: 1fr;
  }
  .review-link {
    margin-left: 0;
  }
}
</style>
