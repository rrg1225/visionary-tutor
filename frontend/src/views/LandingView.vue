<template>
  <div class="landing">
    <section class="hero-shell" data-tour="positioning">
      <div class="hero-copy">
        <span class="vt-eyebrow"
          >VisionaryTutor · 面向高校学生的个性化学习平台</span
        >
        <h1>会讲题、会陪练、会复盘的<br />AI 个性化学习平台</h1>
        <p>
          通过 AI
          老师、固定综合题卷、系统教材、知识测评、交互实验和学习状态辅助，
          帮助学生发现薄弱点并形成个性化学习报告。
        </p>
      </div>

      <div class="hero-cta" aria-label="开始使用">
        <RouterLink class="vt-btn vt-btn-primary hero-button" to="/learn">
          立即问 AI 老师
        </RouterLink>
        <RouterLink class="vt-btn vt-btn-outline hero-button" to="/questions">
          开始综合题卷
        </RouterLink>
        <RouterLink class="vt-btn vt-btn-ghost hero-button" to="/guide">
          查看使用指南
        </RouterLink>
      </div>

      <ul class="hero-differences" aria-label="与普通聊天 AI 的区别">
        <li>有固定教材与题卷</li>
        <li>能保存错题与学习进度</li>
        <li>报告会推动下一轮复习</li>
      </ul>
    </section>

    <section class="ask-panel vt-card-elevated" data-tour="ask">
      <header class="ask-heading">
        <div>
          <span class="vt-eyebrow">也可以直接开始</span>
          <h2>把现在卡住的问题交给 AI 老师</h2>
        </div>
        <span v-if="authStore.isGuest" class="guest-quota"
          >游客剩余 {{ authStore.guestRemainingTurns }} 次</span
        >
      </header>

      <div
        v-if="chat.messages.length || chat.isStreaming"
        class="landing-conversation"
        aria-live="polite"
      >
        <article
          v-for="message in chat.messages"
          :key="message.id"
          class="landing-message"
          :class="`landing-message--${message.role}`"
        >
          <span class="message-role">{{
            message.role === "user" ? "你" : "AI 老师"
          }}</span>
          <MarkdownPanel :content="message.content" />
        </article>

        <article
          v-if="chat.isStreaming"
          class="landing-message landing-message--assistant"
        >
          <span class="message-role">AI 老师</span>
          <MarkdownPanel
            v-if="chat.currentStreamContent"
            :content="chat.currentStreamContent"
          />
          <p v-else class="stream-status">
            {{ chat.streamStatusText || "正在理解你的问题…" }}
          </p>
        </article>
      </div>

      <form class="hero-composer" @submit.prevent="sendQuestion">
        <label class="sr-only" for="landing-question">输入学习问题</label>
        <textarea
          id="landing-question"
          v-model="chat.inputMessage"
          rows="3"
          placeholder="例如：卷积输出尺寸应该怎么计算？请只给提示，带我一步一步做。"
          :disabled="chat.isStreaming"
          @keydown.enter.exact.prevent="sendQuestion"
        ></textarea>
        <div class="composer-footer">
          <span class="profile-hint">
            {{
              userProfile.onboardingComplete && !userProfile.onboardingSkipped
                ? "回答会结合你的学习画像"
                : "无需先建档，画像会在使用中逐步完善"
            }}
          </span>
          <div class="composer-actions">
            <button
              v-if="chat.isStreaming"
              type="button"
              class="vt-btn vt-btn-outline"
              @click="chat.stopStream()"
            >
              停止生成
            </button>
            <button
              v-else
              type="submit"
              class="vt-btn vt-btn-primary"
              :disabled="!chat.inputMessage?.trim()"
            >
              开始提问
            </button>
          </div>
        </div>
      </form>

      <div
        v-if="!chat.messages.length && !chat.isStreaming"
        class="prompt-chips"
        aria-label="问题示例"
      >
        <button
          v-for="prompt in starterPrompts"
          :key="prompt"
          type="button"
          @click="applyPrompt(prompt)"
        >
          {{ prompt }}
        </button>
      </div>
    </section>

    <section class="capabilities" data-tour="capabilities">
      <header class="section-intro">
        <span class="vt-eyebrow">四项核心能力</span>
        <h2>不是多放几个功能，而是让每一步学习能够接上下一步</h2>
      </header>
      <div class="capability-grid">
        <RouterLink
          v-for="item in capabilities"
          :key="item.title"
          :to="item.to"
          class="capability-card vt-card"
        >
          <span class="capability-icon" aria-hidden="true">{{
            item.icon
          }}</span>
          <div>
            <h3>{{ item.title }}</h3>
            <p>{{ item.description }}</p>
          </div>
          <span aria-hidden="true">→</span>
        </RouterLink>
      </div>
    </section>

    <section class="learning-loop vt-card" data-tour="progress">
      <header class="section-intro">
        <span class="vt-eyebrow">完整学习闭环</span>
        <h2>从选择内容，到定位薄弱点，再回到下一轮学习</h2>
      </header>
      <ol class="loop-steps">
        <li v-for="(step, index) in learningLoop" :key="step">
          <span>{{ index + 1 }}</span>
          <strong>{{ step }}</strong>
        </li>
      </ol>
      <div class="loop-action">
        <p>报告不是终点：它会直接指向教材原章节、错题和专项练习。</p>
        <RouterLink
          class="vt-btn vt-btn-outline"
          :to="authStore.isRegistered ? '/my-learning' : '/auth?mode=register'"
        >
          {{ authStore.isRegistered ? "进入我的学习" : "注册并保存学习进度" }}
        </RouterLink>
      </div>
    </section>

    <ProductTour
      :active="guideStore.active"
      :steps="tourSteps"
      :model-value="guideStore.currentStep"
      @update:model-value="guideStore.setStep"
      @complete="completeGuide"
      @skip="skipGuide"
    />
  </div>
</template>

<script setup>
import { onMounted } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import MarkdownPanel from "../components/common/MarkdownPanel.vue";
import ProductTour from "../components/ProductTour.vue";
import { useChatStream } from "../composables/useChatStream";
import { useAuthStore } from "../stores/authStore";
import { useGuideStore } from "../stores/guide";
import { useUserProfileStore } from "../stores/userProfile";

const route = useRoute();
const router = useRouter();
const authStore = useAuthStore();
const userProfile = useUserProfileStore();
const guideStore = useGuideStore();
const chat = useChatStream();

const starterPrompts = [
  "解释卷积输出尺寸，并带我算一个例子",
  "我总是分不清过拟合和欠拟合",
  "帮我分析一段 PyTorch shape 报错",
  "为我规划一条 RAG 入门学习路线",
];

const capabilities = [
  {
    icon: "💬",
    title: "AI 老师答疑",
    description: "结合当前题目、教材上下文和可信知识库持续讲解。",
    to: "/learn",
  },
  {
    icon: "📝",
    title: "题库与知识测评",
    description: "用题卷、错题和作业测评确认真正掌握了什么。",
    to: "/questions",
  },
  {
    icon: "📚",
    title: "系统教材与拓展阅读",
    description: "按章节学习，在阅读中提问并完成教材自考。",
    to: "/library",
  },
  {
    icon: "📈",
    title: "学习状态辅助与报告",
    description: "按需采集学习信号，并与客观答题结果一起复盘。",
    to: "/my-learning",
  },
];

const learningLoop = [
  "选择教材、题卷或直接提问",
  "阅读、做题或完成实验",
  "按需开启学习状态辅助",
  "遇到问题询问 AI 老师",
  "提交自考或整套题卷",
  "生成可信学习报告",
  "定位薄弱点并生成专项练习",
  "返回教材或错题继续学习",
];

const tourSteps = [
  {
    title: "先看清平台定位",
    description:
      "这里不是普通聊天页，而是由教材、题卷、实验、AI 老师和报告组成的学习闭环。",
    selector: '[data-tour="positioning"]',
    rect: null,
  },
  {
    title: "从真实问题开始",
    description:
      "不需要先理解所有菜单，可以直接输入当前卡住的概念、公式、代码或作业题。",
    selector: '[data-tour="ask"]',
    rect: null,
  },
  {
    title: "按任务进入功能",
    description:
      "答疑、测评、教材和学习报告各自承担清晰职责，不再把所有资源塞到一个页面。",
    selector: '[data-tour="capabilities"]',
    rect: null,
  },
  {
    title: "用报告推动下一轮学习",
    description: "学习结束后定位薄弱点，直接返回教材、错题或生成专项练习。",
    selector: '[data-tour="progress"]',
    rect: null,
  },
];

function applyPrompt(prompt) {
  chat.inputMessage = prompt;
  document.querySelector("#landing-question")?.focus();
}

async function sendQuestion() {
  if (!chat.inputMessage?.trim() || chat.isStreaming) return;
  await chat.sendMessage();
}

function clearGuideQuery() {
  if (!route.query.welcome && !route.query.guide) return;
  const query = { ...route.query };
  delete query.welcome;
  delete query.guide;
  void router.replace({ path: "/", query });
}

function completeGuide() {
  guideStore.complete();
  clearGuideQuery();
}

function skipGuide() {
  guideStore.skip();
  clearGuideQuery();
}

onMounted(() => {
  guideStore.hydrate();
  if (authStore.isGuest) chat.loadGuestMessages();
  if (route.query.welcome === "1" || route.query.guide === "1")
    guideStore.start({ force: true });
});
</script>

<style scoped>
.landing {
  width: min(1240px, calc(100% - 32px));
  margin: 0 auto;
  padding: clamp(36px, 6vw, 76px) 0;
  display: grid;
  gap: clamp(36px, 6vw, 72px);
}

.hero-shell {
  width: min(1040px, 100%);
  margin: 0 auto;
  display: grid;
  justify-items: center;
  gap: var(--vt-space-5);
  text-align: center;
}

.hero-copy h1 {
  margin: var(--vt-space-3) 0;
  color: var(--vt-text-primary);
  font-size: clamp(2.25rem, 5.7vw, 4.6rem);
  line-height: 1.06;
  letter-spacing: -0.05em;
}

.hero-copy p {
  max-width: 850px;
  margin: 0 auto;
  color: var(--vt-text-secondary);
  font-size: clamp(1rem, 1.8vw, 1.18rem);
  line-height: 1.8;
}

.hero-cta,
.hero-differences,
.composer-footer,
.composer-actions,
.ask-heading,
.loop-action {
  display: flex;
  align-items: center;
}

.hero-cta {
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--vt-space-3);
}

.hero-button {
  min-width: 174px;
  min-height: 46px;
}

.hero-differences {
  justify-content: center;
  flex-wrap: wrap;
  gap: var(--vt-space-4);
  margin: 0;
  padding: 0;
  list-style: none;
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}

.hero-differences li::before {
  content: "✓";
  margin-right: 6px;
  color: var(--vt-accent-teal-dark);
}

.ask-panel {
  width: min(920px, 100%);
  margin: 0 auto;
  padding: clamp(var(--vt-space-4), 4vw, var(--vt-space-6));
  border: 1px solid rgba(13, 148, 136, 0.2);
}

.ask-heading,
.composer-footer,
.loop-action {
  justify-content: space-between;
  gap: var(--vt-space-4);
}

.ask-heading h2,
.section-intro h2 {
  margin: var(--vt-space-1) 0 0;
  color: var(--vt-text-primary);
}

.ask-heading h2 {
  font-size: var(--vt-text-lg);
}

.landing-conversation {
  max-height: 55vh;
  overflow-y: auto;
  display: grid;
  gap: var(--vt-space-4);
  margin: var(--vt-space-5) 0;
  padding: var(--vt-space-4) 0;
  border-block: 1px solid var(--vt-border-light);
}

.landing-message {
  display: grid;
  gap: var(--vt-space-2);
  min-width: 0;
}

.landing-message--user {
  width: min(80%, 680px);
  margin-left: auto;
  padding: var(--vt-space-3) var(--vt-space-4);
  border-radius: 18px 18px 4px 18px;
  background: var(--vt-accent-primary);
  color: white;
}

.message-role,
.guest-quota,
.profile-hint {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
}

.landing-message--user .message-role {
  color: rgba(255, 255, 255, 0.76);
}

.stream-status {
  margin: 0;
  color: var(--vt-text-secondary);
}

.hero-composer {
  display: grid;
  gap: var(--vt-space-3);
  margin-top: var(--vt-space-3);
}

.hero-composer textarea {
  width: 100%;
  min-height: 104px;
  padding: var(--vt-space-4);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-lg);
  outline: none;
  resize: vertical;
  background: var(--vt-bg-primary);
  color: var(--vt-text-primary);
  font: inherit;
  line-height: 1.65;
}

.composer-actions {
  gap: var(--vt-space-2);
}

.prompt-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
  margin-top: var(--vt-space-4);
}

.prompt-chips button {
  padding: 8px 12px;
  border: 1px solid var(--vt-border-light);
  border-radius: 999px;
  background: var(--vt-surface);
  color: var(--vt-text-secondary);
  cursor: pointer;
  font: inherit;
  font-size: var(--vt-text-xs);
}

.section-intro {
  max-width: 820px;
}

.section-intro h2 {
  font-size: clamp(1.45rem, 3vw, 2.2rem);
  line-height: 1.3;
}

.capabilities {
  display: grid;
  gap: var(--vt-space-5);
}

.capability-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--vt-space-4);
}

.capability-card {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: start;
  gap: var(--vt-space-3);
  padding: var(--vt-space-5);
  color: inherit;
  text-decoration: none;
}

.capability-card:hover {
  transform: translateY(-3px);
  border-color: rgba(13, 148, 136, 0.35);
}

.capability-card h3 {
  margin: 0;
  color: var(--vt-text-primary);
  font-size: var(--vt-text-base);
}

.capability-card p {
  margin: var(--vt-space-2) 0 0;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-sm);
  line-height: 1.65;
}

.capability-icon {
  font-size: 1.55rem;
}

.learning-loop {
  display: grid;
  gap: var(--vt-space-5);
  padding: clamp(var(--vt-space-5), 5vw, var(--vt-space-8));
}

.loop-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--vt-space-3);
  margin: 0;
  padding: 0;
  list-style: none;
}

.loop-steps li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: var(--vt-space-2);
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
}

.loop-steps span {
  display: grid;
  width: 1.65rem;
  height: 1.65rem;
  place-items: center;
  border-radius: 999px;
  background: rgba(13, 148, 136, 0.12);
  color: var(--vt-accent-teal-dark);
  font-size: var(--vt-text-xs);
}

.loop-steps strong {
  font-size: var(--vt-text-sm);
  line-height: 1.45;
}

.loop-action p {
  margin: 0;
  color: var(--vt-text-secondary);
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 980px) {
  .capability-grid,
  .loop-steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .landing {
    width: min(100% - 20px, 1240px);
    padding: 28px 0 40px;
  }

  .hero-copy h1 br {
    display: none;
  }

  .hero-cta,
  .ask-heading,
  .composer-footer,
  .loop-action {
    align-items: stretch;
    flex-direction: column;
  }

  .hero-button,
  .composer-actions > *,
  .loop-action .vt-btn {
    width: 100%;
  }

  .capability-grid,
  .loop-steps {
    grid-template-columns: 1fr;
  }

  .landing-message--user {
    width: 92%;
  }
}
</style>
