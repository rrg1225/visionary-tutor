<template>
  <main class="knowledge-reader">
    <div v-if="loading" class="reader-state vt-card">正在加载经过来源校验的系统内容…</div>
    <div v-else-if="error" class="reader-state vt-card">
      <h1>内容暂时无法加载</h1>
      <p>{{ error }}</p>
      <RouterLink class="vt-btn vt-btn-outline" to="/library">返回内容中心</RouterLink>
    </div>
    <template v-else-if="item">
      <header class="reader-header vt-card">
        <div>
          <div class="reader-badges">
            <span>{{ kindLabel(item.summary.kind) }}</span>
            <span>{{ item.summary.subject }}</span>
            <span>{{ item.summary.difficulty }}</span>
            <span>{{ item.summary.estimatedMinutes }} 分钟</span>
          </div>
          <h1>{{ item.summary.title }}</h1>
          <p>{{ item.summary.description }}</p>
          <small>
            {{ item.summary.authorLabel }} · {{ item.summary.venue }} ·
            {{ item.summary.licenseName }}
          </small>
        </div>
        <RouterLink class="vt-btn vt-btn-ghost" to="/library">返回内容中心</RouterLink>
      </header>

      <LearningStateAssist
        context-type="SYSTEM_KNOWLEDGE"
        :context-key="item.summary.slug"
        :context-title="item.summary.title"
      />

      <details class="mobile-drawer vt-card">
        <summary>章节目录与学习目标</summary>
        <ChapterDirectory
          :sections="item.sections"
          :active-id="activeSectionId"
          @select="selectSection"
        />
      </details>

      <section class="reader-grid">
        <aside class="reader-directory vt-card">
          <ChapterDirectory
            :sections="item.sections"
            :active-id="activeSectionId"
            @select="selectSection"
          />
          <div class="progress-box">
            <span>阅读进度</span>
            <strong>{{ progress }}%</strong>
            <progress :value="progress" max="100"></progress>
          </div>
        </aside>

        <article class="reader-body vt-card">
          <section class="chapter-guide">
            <span class="vt-eyebrow">当前章节导航</span>
            <h2>{{ activeSection.title }}</h2>
            <p>{{ activeSection.summary }}</p>
            <div class="guide-columns">
              <div>
                <strong>读完后请思考</strong>
                <ol>
                  <li v-for="question in activeSection.reflectionQuestions" :key="question">
                    {{ question }}
                  </li>
                </ol>
              </div>
              <div>
                <strong>常见误区</strong>
                <ul>
                  <li v-for="mistake in activeSection.commonErrors" :key="mistake">
                    {{ mistake }}
                  </li>
                </ul>
              </div>
            </div>
          </section>

          <DocumentResourceCard
            :content="item.contentMarkdown"
            :title="item.summary.title"
            :enable-tutor="false"
          />

          <section id="self-test" class="self-test">
            <div class="self-test-heading">
              <div>
                <span class="vt-eyebrow">整篇自测</span>
                <h2>先作答，提交后再显示答案与解析</h2>
              </div>
              <span class="draft-status">{{ draftStatus }}</span>
            </div>
            <p class="test-policy">
              自测草稿仅保存在当前浏览器。提交前 AI 老师只提供思路提示；提交后才可讨论答案。
            </p>
            <article v-for="(question, index) in questions" :key="question.id" class="test-question">
              <strong>{{ index + 1 }}. {{ question.prompt }}</strong>
              <label v-for="choice in question.choices" :key="choice">
                <input
                  v-model="answers[question.id]"
                  type="radio"
                  :name="question.id"
                  :value="choice"
                  :disabled="submitted"
                  @change="saveDraft"
                />
                <span>{{ choice }}</span>
              </label>
              <div v-if="submitted" class="answer-review" :class="answerClass(question)">
                <strong>{{ answers[question.id] === question.answer ? "回答正确" : "需要复习" }}</strong>
                <p>参考答案：{{ question.answer }}</p>
                <p>{{ question.explanation }}</p>
              </div>
            </article>
            <div class="test-actions">
              <button
                v-if="!submitted"
                type="button"
                class="vt-btn vt-btn-primary"
                :disabled="answeredCount !== questions.length"
                @click="submitTest"
              >
                提交并评分（{{ answeredCount }}/{{ questions.length }}）
              </button>
              <button v-else type="button" class="vt-btn vt-btn-outline" @click="restartTest">
                重新自测
              </button>
            </div>
            <div v-if="report" class="test-report vt-card">
              <div>
                <span>本次得分</span>
                <strong>{{ report.score }} / {{ report.maxScore }}</strong>
              </div>
              <p>{{ report.summary }}</p>
              <div class="report-links">
                <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm" @click="scrollToChapter">
                  回到薄弱章节
                </button>
                <RouterLink class="vt-btn vt-btn-ghost vt-btn-sm" to="/questions">
                  查看错题本
                </RouterLink>
                <RouterLink class="vt-btn vt-btn-ghost vt-btn-sm" to="/questions/personalized">
                  生成专项练习
                </RouterLink>
              </div>
            </div>
          </section>

          <section class="source-panel">
            <h2>来源与使用说明</h2>
            <p>
              目录版本 {{ item.summary.catalogVersion }}；状态 {{ item.summary.reviewStatus }}。
              “团队复核”表示内容虽已通过结构与来源校验，仍应由项目团队在正式发布前完成最终人工审阅。
            </p>
            <ul>
              <li v-for="source in item.summary.sources" :key="source.url">
                <a :href="source.url" target="_blank" rel="noreferrer">{{ source.title }}</a>
                · {{ source.sourceType }} · {{ source.licenseName || "见原站条款" }}
              </li>
            </ul>
          </section>
        </article>

        <aside class="reader-tutor">
          <ContextualTutorPanel
            title="阅读 AI 老师"
            :context="tutorContext"
            :learning-session-id="learningSessionStore.currentSessionId"
            context-type="SYSTEM_KNOWLEDGE"
            :context-key="item.summary.slug"
            :context-title="item.summary.title"
            :answer-mode="submitted ? 'ANSWER_REVEALED' : 'HINT_ONLY'"
          />
        </aside>
      </section>

      <details class="mobile-drawer mobile-tutor vt-card">
        <summary>打开阅读 AI 老师</summary>
        <ContextualTutorPanel
          title="阅读 AI 老师"
          :context="tutorContext"
          :learning-session-id="learningSessionStore.currentSessionId"
          context-type="SYSTEM_KNOWLEDGE"
          :context-key="item.summary.slug"
          :context-title="item.summary.title"
          :answer-mode="submitted ? 'ANSWER_REVEALED' : 'HINT_ONLY'"
        />
      </details>
    </template>
  </main>
</template>

<script setup>
import { computed, defineComponent, h, onMounted, reactive, ref } from "vue";
import { RouterLink, useRoute } from "vue-router";
import ContextualTutorPanel from "../components/ContextualTutorPanel.vue";
import LearningStateAssist from "../components/LearningStateAssist.vue";
import DocumentResourceCard from "../components/resource/cards/DocumentResourceCard.vue";
import { getSystemKnowledgeContent } from "../api/knowledgeContent";
import { recordQuestionAttempts } from "../api/questionBank";
import { useAuthStore } from "../stores/authStore";
import { useLearningSessionStore } from "../stores/learningSession";

const ChapterDirectory = defineComponent({
  props: { sections: { type: Array, default: () => [] }, activeId: { type: String, default: "" } },
  emits: ["select"],
  setup(props, { emit }) {
    return () => h("div", { class: "chapter-directory" }, [
      h("span", { class: "vt-eyebrow" }, "章节目录"),
      h("ol", props.sections.map((section) => h("li", { key: section.id }, [
        h("button", {
          type: "button",
          class: { active: props.activeId === section.id },
          onClick: () => emit("select", section.id),
        }, `${section.order}. ${section.title}`),
      ]))),
    ]);
  },
});

const route = useRoute();
const authStore = useAuthStore();
const learningSessionStore = useLearningSessionStore();
const item = ref(null);
const loading = ref(true);
const error = ref("");
const activeSectionId = ref("");
const answers = reactive({});
const submitted = ref(false);
const report = ref(null);
const draftStatus = ref("尚未作答");

const activeSection = computed(() =>
  item.value?.sections?.find((section) => section.id === activeSectionId.value)
  || item.value?.sections?.[0]
  || { title: "", summary: "", reflectionQuestions: [], commonErrors: [] },
);
const progress = computed(() => {
  const sections = item.value?.sections || [];
  const index = sections.findIndex((section) => section.id === activeSectionId.value);
  return sections.length ? Math.max(1, Math.round(((index + 1) / sections.length) * 100)) : 0;
});
const questions = computed(() => buildQuestions(item.value));
const answeredCount = computed(() => questions.value.filter((question) => answers[question.id]).length);
const tutorContext = computed(() => {
  if (!item.value) return "";
  const section = activeSection.value;
  const selfTestContext = questions.value.map((question, index) => {
    const lines = [
      `自测第 ${index + 1} 题：${question.prompt}`,
      `选项：${question.choices.map((choice, choiceIndex) => `${choiceIndex + 1}. ${choice}`).join("；")}`,
      answers[question.id]
        ? `学习者选择：${answers[question.id]}`
        : "学习者尚未作答",
    ];
    if (submitted.value) {
      lines.push(`正确答案：${question.answer}`, `解析：${question.explanation}`);
    } else {
      lines.push("正确答案在提交前未向 AI 开放；不得猜测、推导或泄露答案。");
    }
    return lines.join("\n");
  }).join("\n\n");
  const selfTestBoundary = submitted.value
    ? `自测已提交。得分 ${report.value?.score || 0}/${report.value?.maxScore || questions.value.length}，可以讲解答案。`
    : "自测尚未提交。涉及自测时只能给提示和检查思路，禁止泄露最终答案。";
  return [
    `内容：${item.value.summary.title}`,
    `当前章节：${section.title}\n${section.summary}`,
    `反思问题：${section.reflectionQuestions.join("；")}`,
    selfTestBoundary,
    `页面自测（用户所说的“第 N 题”优先指这里）：\n${selfTestContext}`,
    item.value.contentMarkdown.slice(0, 14000),
  ].join("\n\n");
});

function kindLabel(kind) {
  return { BOOK: "章节式专题书", ARTICLE: "工程文章", PAPER: "论文导读", REVIEW: "综述导读" }[kind] || kind;
}

function selectSection(id) {
  activeSectionId.value = id;
  persistProgress();
  globalThis.scrollTo?.({ top: 0, behavior: "smooth" });
}

function buildQuestions(content) {
  const sections = content?.sections || [];
  if (!sections.length) return [];
  return sections.slice(0, 5).map((section, index) => {
    const alternatives = sections
      .filter((candidate) => candidate.id !== section.id)
      .map((candidate) => candidate.summary);
    const generic = [
      "只需记住名词，不必验证形状、假设或实验条件。",
      "结论在任何数据、模型和评估协议下都完全相同。",
      "只比较最终分数即可，不需要建立可复现基线。",
    ];
    return {
      id: `${content.summary.slug}-${section.id}`,
      prompt: `关于“${section.title}”，哪一项最准确地概括了本节学习目标？`,
      choices: stableShuffle([section.summary, ...alternatives, ...generic].slice(0, 4), index),
      answer: section.summary,
      explanation: `本节反思入口：${section.reflectionQuestions[0]} 常见误区：${section.commonErrors[0]}。`,
      sectionId: section.id,
    };
  });
}

function stableShuffle(values, seed) {
  const result = [...new Set(values)];
  if (result.length > 1) {
    const offset = seed % result.length;
    return [...result.slice(offset), ...result.slice(0, offset)];
  }
  return result;
}

function storageKey(suffix) {
  return `vt_knowledge_${route.params.slug}_${suffix}`;
}

function saveDraft() {
  localStorage.setItem(storageKey("draft"), JSON.stringify({ ...answers }));
  draftStatus.value = `草稿已保存 · ${answeredCount.value}/${questions.value.length}`;
}

function submitTest() {
  if (answeredCount.value !== questions.value.length) return;
  submitted.value = true;
  const incorrect = questions.value.filter((question) => answers[question.id] !== question.answer);
  const score = questions.value.length - incorrect.length;
  const syncedToBackend = authStore.isRegistered;
  report.value = {
    score,
    maxScore: questions.value.length,
    submittedAt: new Date().toISOString(),
    weakSectionIds: incorrect.map((question) => question.sectionId),
    summary: incorrect.length
      ? `有 ${incorrect.length} 个章节需要复习，错题${syncedToBackend ? "已进入错题本，可在题库页安排复习" : "已记录在本机（登录后可同步到错题本）"}。`
      : "所有章节导航题均正确。建议继续用反思题检验是否能独立解释。",
  };
  localStorage.setItem(storageKey("report"), JSON.stringify(report.value));
  if (syncedToBackend) {
    syncAttemptsToWrongBook();
  } else {
    persistGuestWrongBook(incorrect);
  }
  persistProgress(true);
}

/** 注册用户：自测结果进入后端错题本与复习计划（问题14/18 的教材→错题本闭环）。 */
function syncAttemptsToWrongBook() {
  const slug = item.value.summary.slug;
  const attempts = questions.value.map((question) => ({
    learningSessionId: learningSessionStore.currentSessionId,
    questionKey: `content:${slug}:${question.id}`,
    prompt: question.prompt,
    userAnswer: answers[question.id] || "",
    correctAnswer: question.answer,
    explanation: question.explanation,
    concept: item.value.summary.title,
    correct: answers[question.id] === question.answer,
    skipped: false,
    sourceType: "CONTENT_SELF_TEST",
    sourceQuestionId: question.id,
  }));
  recordQuestionAttempts(attempts).catch(() => {
    // 错题本暂时不可用时保留本机记录，避免丢失本次自测结果。
    persistGuestWrongBook(questions.value.filter((question) => answers[question.id] !== question.answer));
  });
}

/** 游客或后端不可用时的本机兜底记录。 */
function persistGuestWrongBook(incorrect) {
  const wrongbook = JSON.parse(localStorage.getItem("vt_content_wrongbook") || "[]");
  const remaining = wrongbook.filter((entry) => entry.contentSlug !== item.value.summary.slug);
  localStorage.setItem("vt_content_wrongbook", JSON.stringify([
    ...remaining,
    ...incorrect.map((question) => ({
      contentSlug: item.value.summary.slug,
      contentTitle: item.value.summary.title,
      sectionId: question.sectionId,
      question: question.prompt,
      learnerAnswer: answers[question.id],
      correctAnswer: question.answer,
      createdAt: report.value.submittedAt,
    })),
  ]));
}

function restartTest() {
  Object.keys(answers).forEach((key) => delete answers[key]);
  submitted.value = false;
  report.value = null;
  draftStatus.value = "已开始新一轮自测";
  localStorage.removeItem(storageKey("draft"));
}

function answerClass(question) {
  return answers[question.id] === question.answer ? "correct" : "incorrect";
}

function scrollToChapter() {
  const first = report.value?.weakSectionIds?.[0];
  if (first) activeSectionId.value = first;
  globalThis.scrollTo?.({ top: 0, behavior: "smooth" });
}

function persistProgress(completed = false) {
  const state = JSON.parse(localStorage.getItem("vt_knowledge_progress") || "{}");
  state[item.value.summary.slug] = {
    slug: item.value.summary.slug,
    title: item.value.summary.title,
    kind: item.value.summary.kind,
    progress: completed ? 100 : progress.value,
    activeSectionId: activeSectionId.value,
    selfTestCompleted: completed,
    updatedAt: new Date().toISOString(),
  };
  localStorage.setItem("vt_knowledge_progress", JSON.stringify(state));
}

function restoreLocalState() {
  const progressState = JSON.parse(localStorage.getItem("vt_knowledge_progress") || "{}");
  activeSectionId.value = progressState[item.value.summary.slug]?.activeSectionId
    || item.value.sections[0]?.id
    || "";
  const draft = JSON.parse(localStorage.getItem(storageKey("draft")) || "{}");
  Object.assign(answers, draft);
  draftStatus.value = Object.keys(draft).length ? `已恢复草稿 · ${Object.keys(draft).length}/${questions.value.length}` : "尚未作答";
  const savedReport = JSON.parse(localStorage.getItem(storageKey("report")) || "null");
  if (savedReport) {
    report.value = savedReport;
    submitted.value = true;
  }
}

onMounted(async () => {
  try {
    const [content] = await Promise.all([
      getSystemKnowledgeContent(String(route.params.slug)),
      learningSessionStore.ensureCurrentSession("系统内容深度阅读").catch(() => null),
    ]);
    item.value = content;
    restoreLocalState();
    persistProgress();
  } catch (reason) {
    error.value = reason?.response?.data?.message || reason?.message || "请稍后重试。";
  } finally {
    loading.value = false;
  }
});
</script>

<style scoped>
.knowledge-reader { max-width: 1600px; margin: 0 auto; padding: 1rem; display: grid; gap: 1rem; }
.reader-state, .reader-header, .reader-directory, .reader-body { padding: 1.2rem; }
.reader-header { display: flex; justify-content: space-between; gap: 1rem; background: linear-gradient(135deg, rgba(15,118,110,.08), rgba(79,70,229,.06)); }
.reader-header h1 { margin: .45rem 0; }
.reader-header p { max-width: 850px; line-height: 1.7; }
.reader-badges { display: flex; flex-wrap: wrap; gap: .45rem; }
.reader-badges span { border-radius: 999px; padding: .2rem .55rem; background: rgba(15,118,110,.1); font-size: .82rem; }
.reader-grid { display: grid; grid-template-columns: 230px minmax(0, 1fr) 340px; gap: 1rem; align-items: start; }
.reader-directory, .reader-tutor { position: sticky; top: 82px; max-height: calc(100vh - 100px); overflow: auto; }
:deep(.chapter-directory ol) { list-style: none; padding: 0; display: grid; gap: .35rem; }
:deep(.chapter-directory button) { width: 100%; border: 0; background: transparent; border-radius: 8px; padding: .55rem; text-align: left; cursor: pointer; line-height: 1.4; }
:deep(.chapter-directory button.active) { background: rgba(15,118,110,.1); color: #0f766e; font-weight: 700; }
.progress-box { display: grid; grid-template-columns: 1fr auto; gap: .45rem; margin-top: 1rem; font-size: .85rem; }
.progress-box progress { grid-column: 1 / -1; width: 100%; }
.reader-body { min-width: 0; display: grid; gap: 1.25rem; }
.chapter-guide { padding: 1rem; border-radius: 12px; background: rgba(79,70,229,.055); }
.chapter-guide h2 { margin: .35rem 0; }
.guide-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 1rem; }
.guide-columns li { margin: .4rem 0; line-height: 1.5; }
.self-test { border-top: 1px solid rgba(148,163,184,.35); padding-top: 1.25rem; display: grid; gap: 1rem; }
.self-test-heading { display: flex; justify-content: space-between; gap: 1rem; }
.self-test-heading h2 { margin: .35rem 0; }
.draft-status, .test-policy { color: var(--vt-text-secondary); font-size: .86rem; }
.test-question { display: grid; gap: .55rem; padding: 1rem; border: 1px solid rgba(148,163,184,.25); border-radius: 12px; }
.test-question label { display: flex; align-items: flex-start; gap: .5rem; cursor: pointer; }
.answer-review { border-radius: 8px; padding: .75rem; }
.answer-review.correct { background: rgba(16,185,129,.08); color: #047857; }
.answer-review.incorrect { background: rgba(239,68,68,.07); color: #b91c1c; }
.answer-review p { margin: .35rem 0 0; }
.test-report { padding: 1rem; background: rgba(15,118,110,.05); }
.test-report > div:first-child { display: flex; justify-content: space-between; }
.test-report strong { font-size: 1.4rem; }
.report-links { display: flex; flex-wrap: wrap; gap: .5rem; }
.source-panel { border-top: 1px solid rgba(148,163,184,.35); }
.source-panel li { margin: .45rem 0; }
.mobile-drawer { display: none; padding: .8rem; }
@media (max-width: 1180px) {
  .reader-grid { grid-template-columns: 210px minmax(0, 1fr); }
  .reader-tutor { display: none; }
  .mobile-tutor { display: block; }
}
@media (max-width: 760px) {
  .reader-header { flex-direction: column; }
  .reader-grid { display: block; }
  .reader-directory { display: none; }
  .mobile-drawer { display: block; }
  .reader-body { margin-top: 1rem; padding: .85rem; }
  .guide-columns { grid-template-columns: 1fr; }
  .self-test-heading { flex-direction: column; }
}
</style>
