<template>
  <main class="reader-page">
    <RouterLink class="back-link" to="/library" aria-label="返回共享教材库">
      <span aria-hidden="true">←</span>
      返回共享教材库
    </RouterLink>

    <section v-if="loading" class="reader-state vt-card" aria-live="polite">
      <span class="state-spinner" aria-hidden="true"></span>
      <div>
        <strong>正在打开教材</strong>
        <p>正在整理正文与阅读视图…</p>
      </div>
    </section>

    <section v-else-if="error" class="reader-state reader-error vt-card" role="alert">
      <span class="state-icon" aria-hidden="true">!</span>
      <div>
        <strong>教材暂时无法打开</strong>
        <p>{{ error }}</p>
        <RouterLink class="vt-btn vt-btn-outline vt-btn-sm" to="/library">返回教材库</RouterLink>
      </div>
    </section>

    <template v-else-if="book">
      <header class="reader-hero vt-card">
        <div class="hero-glow hero-glow-one"></div>
        <div class="hero-glow hero-glow-two"></div>
        <div class="hero-content">
          <div class="reader-meta">
            <span class="subject-pill">{{ book.subjectTag || 'general' }}</span>
            <span v-if="book.isShowcaseFallback" class="sample-pill">内置示例</span>
            <span>{{ book.viewCount || 0 }} 次阅读</span>
            <span v-if="formattedDate">{{ formattedDate }}</span>
          </div>
          <h1>{{ book.title }}</h1>
          <p v-if="book.description" class="reader-description">
            {{ book.description }}
          </p>
        </div>
        <div class="hero-mark" aria-hidden="true"><span></span><span></span><span></span></div>
      </header>

      <div class="reader-layout">
        <aside class="reader-outline vt-card" aria-label="教材目录">
          <span class="tool-eyebrow">章节目录</span>
          <nav v-if="outline.length">
            <a v-for="item in outline" :key="item.id" :href="`#${item.id}`" :class="`depth-${item.depth}`">
              {{ item.title }}
            </a>
          </nav>
          <p v-else>这份内容暂未设置章节标题。</p>
        </aside>

        <article class="reader-article vt-card">
          <div class="article-kicker">
            <span>教材正文</span>
            <span class="reading-time">约 {{ readingMinutes }} 分钟阅读</span>
          </div>
          <DocumentResourceCard
            class="reader-markdown"
            :content="book.contentMarkdown"
            :title="book.title"
            :enable-tutor="false"
          />
          <footer class="article-footer">
            <div>
              <strong>读完了？</strong>
              <p>回到教材库继续浏览其他社区学习材料。</p>
            </div>
            <RouterLink class="vt-btn vt-btn-primary" to="/library">继续探索教材</RouterLink>
          </footer>
        </article>

        <aside class="reader-tools" aria-label="阅读工具">
          <LearningStateAssist
            context-type="COMMUNITY_TEXTBOOK"
            :context-key="`community:${book.id}`"
            :context-title="book.title"
          />
          <section class="tool-card tool-card-tutor vt-card">
            <ContextualTutorPanel
              title="阅读 AI 老师"
              :context="tutorContext"
              :learning-session-id="learningSessionStore.currentSessionId"
              context-type="COMMUNITY_TEXTBOOK"
              :context-key="`community:${book.id}`"
              :context-title="book.title"
              :quick-actions="readingQuickActions"
            />
          </section>

          <section class="tool-card vt-card">
            <span class="tool-eyebrow">阅读工具</span>
            <h2>专注阅读</h2>
            <p>正文已在独立页面展示，滚动时工具栏会保持在侧边。</p>
            <div v-if="ttsSupported" class="speech-actions">
              <button type="button" class="vt-btn vt-btn-outline" @click="toggleSpeech">
                {{ isSpeaking ? '停止朗读' : '朗读全文' }}
              </button>
              <button v-if="isSpeaking" type="button" class="vt-btn vt-btn-ghost" @click="tts.stop()">停止</button>
            </div>
          </section>

          <section class="tool-card tool-card-accent vt-card">
            <span class="tool-eyebrow">来源说明</span>
            <p v-if="book.isShowcaseFallback">这是后端教材不可用时展示的只读示例内容。</p>
            <template v-else>
              <p><strong>{{ sourceTypeLabel }}</strong> · 公开内容已通过平台审核后才进入 RAG。</p>
              <dl class="source-list">
                <template v-if="book.sourceTitle">
                  <dt>原始资料</dt>
                  <dd>{{ book.sourceTitle }}</dd>
                </template>
                <template v-if="book.licenseName">
                  <dt>许可/授权</dt>
                  <dd>{{ book.licenseName }}</dd>
                </template>
                <template v-if="book.rightsStatement">
                  <dt>权利说明</dt>
                  <dd>{{ book.rightsStatement }}</dd>
                </template>
              </dl>
              <a v-if="sourceUrlHref" class="source-link" :href="sourceUrlHref" target="_blank" rel="noopener noreferrer">查看原始链接</a>
            </template>
          </section>
        </aside>
      </div>
    </template>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { getTextbook } from '../api/library'
import { useTextToSpeech } from '../composables/useTextToSpeech'
import { useLearningSessionStore } from '../stores/learningSession'
import { SHOWCASE_TEXTBOOKS_FALLBACK } from '../data/showcaseFallback'
import ContextualTutorPanel from '../components/ContextualTutorPanel.vue'
import DocumentResourceCard from '../components/resource/cards/DocumentResourceCard.vue'
import LearningStateAssist from '../components/LearningStateAssist.vue'
import { markdownHeadingId } from '../utils/simpleMarkdown'

const route = useRoute()
const tts = useTextToSpeech()
const ttsSupported = tts.supported
const isSpeaking = tts.isSpeaking
const learningSessionStore = useLearningSessionStore()
const book = ref(null)
const loading = ref(true)
const error = ref('')
const outline = computed(() => String(book.value?.contentMarkdown || '')
  .split(/\r?\n/)
  .map((line, index) => {
    const match = line.match(/^\s*(#{2,3})\s+(.+?)\s*#*\s*$/)
    return match ? { id: markdownHeadingId(index), depth: match[1].length, title: match[2] } : null
  })
  .filter(Boolean))

const readingQuickActions = [
  '总结这篇内容的核心观点',
  '换一种简单讲法',
  '举一个类似例子',
  '这部分和我学过的知识有什么联系',
  '出几道自测问题考考我',
]

const tutorContext = computed(() => {
  if (!book.value) return ''
  return [
    `社区内容：${book.value.title}`,
    book.value.description ? `简介：${book.value.description}` : '',
    book.value.sourceTitle ? `原始资料：${book.value.sourceTitle}` : '',
    `正文（截取）：\n${String(book.value.contentMarkdown || '').slice(0, 14000)}`,
  ].filter(Boolean).join('\n\n')
})

const readingMinutes = computed(() => {
  const contentLength = String(book.value?.contentMarkdown || '').replace(/\s/g, '').length
  return Math.max(1, Math.ceil(contentLength / 420))
})
const sourceTypeLabel = computed(() => ({
  original: '本人原创',
  personal_notes: '基于资料整理的个人笔记',
  open_license: '开放许可材料',
  authorized: '已获得授权',
  legacy_import: '历史导入材料',
}[book.value?.sourceType] || '来源信息已登记'))
const sourceUrlHref = computed(() => {
  const value = String(book.value?.sourceUrl || '').trim()
  if (!value) return ''
  try {
    const parsed = new URL(value)
    return ['http:', 'https:'].includes(parsed.protocol) ? parsed.href : ''
  } catch {
    return ''
  }
})
const formattedDate = computed(() => {
  if (!book.value?.createdAt) return ''
  const date = new Date(book.value.createdAt)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'long',
    day: 'numeric',
  }).format(date)
})

async function loadBook() {
  loading.value = true
  error.value = ''
  const id = String(route.params.id || '')
  const fallback = SHOWCASE_TEXTBOOKS_FALLBACK.find((item) => String(item.id) === id)
  if (fallback) {
    book.value = { ...fallback, isShowcaseFallback: true }
    loading.value = false
    return
  }

  try {
    book.value = await getTextbook(id)
  } catch (requestError) {
    error.value = requestError?.response?.data?.message || requestError?.message || '请稍后重试。'
  } finally {
    loading.value = false
  }
}

function toggleSpeech() {
  if (tts.isSpeaking.value) {
    tts.stop()
    return
  }
  tts.speak([book.value?.title, book.value?.description, book.value?.contentMarkdown].filter(Boolean).join('。'))
}

onMounted(() => {
  void loadBook()
  learningSessionStore.ensureCurrentSession('社区内容深度阅读').catch(() => null)
})
onBeforeUnmount(() => tts.stop())
</script>

<style scoped>
.reader-page {
  width: min(1480px, calc(100% - 2rem));
  margin: 0 auto;
  padding: 1.5rem 0 4rem;
  display: grid;
  gap: 1.1rem;
}

.back-link {
  width: fit-content;
  display: inline-flex;
  align-items: center;
  gap: 0.55rem;
  color: var(--vt-text-secondary);
  font-size: 0.92rem;
  font-weight: 600;
  text-decoration: none;
  transition:
    color 160ms ease,
    transform 160ms ease;
}

.back-link:hover {
  color: var(--vt-accent-teal);
  transform: translateX(-3px);
}

.reader-hero {
  position: relative;
  min-height: 280px;
  overflow: hidden;
  padding: clamp(2rem, 5vw, 4.5rem);
  display: flex;
  align-items: center;
  background: linear-gradient(128deg, #111827 0%, #173c48 58%, #0f766e 130%);
  color: white;
  border: 0;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.2);
}

.hero-content {
  position: relative;
  z-index: 2;
  max-width: 790px;
}

.reader-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.65rem 1rem;
  margin-bottom: 1.15rem;
  color: rgba(255, 255, 255, 0.68);
  font-size: 0.84rem;
}

.subject-pill,
.sample-pill {
  padding: 0.32rem 0.72rem;
  border-radius: 999px;
  font-weight: 700;
}

.subject-pill {
  color: #ccfbf1;
  background: rgba(45, 212, 191, 0.16);
  border: 1px solid rgba(94, 234, 212, 0.24);
}

.sample-pill {
  color: #e0e7ff;
  background: rgba(129, 140, 248, 0.18);
}

.reader-hero h1 {
  max-width: 760px;
  margin: 0;
  font-size: clamp(2rem, 5vw, 3.65rem);
  line-height: 1.12;
  letter-spacing: -0.04em;
}

.reader-description {
  max-width: 680px;
  margin: 1.25rem 0 0;
  color: rgba(255, 255, 255, 0.74);
  font-size: clamp(1rem, 2vw, 1.15rem);
  line-height: 1.8;
}

.hero-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(4px);
  pointer-events: none;
}

.hero-glow-one {
  width: 360px;
  height: 360px;
  right: -90px;
  top: -160px;
  background: rgba(45, 212, 191, 0.2);
}

.hero-glow-two {
  width: 250px;
  height: 250px;
  left: 36%;
  bottom: -190px;
  background: rgba(129, 140, 248, 0.2);
}

.hero-mark {
  position: absolute;
  right: clamp(1.5rem, 5vw, 4rem);
  bottom: 2.5rem;
  display: flex;
  align-items: end;
  gap: 0.5rem;
  opacity: 0.28;
}

.hero-mark span {
  width: 10px;
  border-radius: 999px;
  background: white;
}

.hero-mark span:nth-child(1) {
  height: 42px;
}
.hero-mark span:nth-child(2) {
  height: 70px;
}
.hero-mark span:nth-child(3) {
  height: 54px;
}

.reader-layout {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) 340px;
  gap: 1.1rem;
  align-items: start;
}

.reader-outline {
  position: sticky;
  top: 96px;
  padding: 1rem;
  display: grid;
  gap: .75rem;
  max-height: calc(100vh - 120px);
  overflow: auto;
}

.reader-outline nav { display: grid; gap: .25rem; }
.reader-outline a { padding: .45rem .55rem; border-radius: .5rem; color: var(--vt-text-secondary); font-size: .82rem; line-height: 1.4; text-decoration: none; }
.reader-outline a:hover { background: rgba(13, 148, 136, .08); color: var(--vt-accent-teal-dark); }
.reader-outline a.depth-3 { padding-left: 1.2rem; font-size: .78rem; }
.reader-outline p { margin: 0; color: var(--vt-text-tertiary); font-size: .8rem; line-height: 1.5; }

.reader-article {
  min-width: 0;
  padding: clamp(1.5rem, 4vw, 3.5rem);
  border: 1px solid rgba(148, 163, 184, 0.16);
}

.article-kicker {
  display: flex;
  justify-content: space-between;
  gap: 1rem;
  padding-bottom: 1.25rem;
  margin-bottom: 2rem;
  border-bottom: 1px solid var(--vt-border-light);
  color: var(--vt-accent-teal);
  font-size: 0.82rem;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.reading-time {
  color: var(--vt-text-tertiary);
  letter-spacing: 0;
  font-weight: 500;
}

.reader-markdown {
  font-size: 1.02rem;
  line-height: 1.9;
  color: var(--vt-text-primary);
}

.reader-markdown :deep(h1) {
  display: none;
}

.reader-markdown :deep(h2) {
  margin-top: 2.4rem;
  padding-left: 0.85rem;
  border-left: 4px solid var(--vt-accent-teal);
  font-size: 1.5rem;
}

.reader-markdown :deep(li) {
  margin: 0.55rem 0;
}

.article-footer {
  margin-top: 3rem;
  padding: 1.25rem;
  border-radius: var(--vt-radius-lg);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  background: linear-gradient(135deg, rgba(20, 184, 166, 0.08), rgba(99, 102, 241, 0.06));
}

.article-footer p {
  margin: 0.3rem 0 0;
  color: var(--vt-text-secondary);
  font-size: 0.88rem;
}

.reader-tools {
  position: sticky;
  top: 96px;
  display: grid;
  gap: 1rem;
}

.tool-card {
  padding: 1.25rem;
  display: grid;
  gap: 0.8rem;
  border: 1px solid rgba(148, 163, 184, 0.16);
}

.tool-card-tutor {
  padding: 0.85rem;
}

.tool-card h2,
.tool-card p {
  margin: 0;
}

.source-list {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 0.35rem 0.65rem;
  margin: 0;
  font-size: 0.8rem;
  line-height: 1.5;
}

.source-list dt {
  color: var(--vt-text-tertiary);
}

.source-list dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: var(--vt-text-secondary);
}

.source-link {
  color: var(--vt-accent-teal-dark);
  font-size: 0.82rem;
  font-weight: 650;
}

.tool-card h2 {
  font-size: 1.15rem;
}

.tool-card p {
  color: var(--vt-text-secondary);
  font-size: 0.88rem;
  line-height: 1.65;
}

.tool-eyebrow {
  color: var(--vt-accent-teal);
  font-size: 0.76rem;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.speech-actions {
  display: grid;
  gap: 0.45rem;
}

.tool-card-accent {
  background: linear-gradient(145deg, rgba(204, 251, 241, 0.55), rgba(238, 242, 255, 0.7));
}

.reader-state {
  min-height: 260px;
  padding: 2rem;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 1rem;
  text-align: left;
}

.reader-state p {
  margin: 0.35rem 0 1rem;
  color: var(--vt-text-secondary);
}

.state-spinner {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 3px solid rgba(13, 148, 136, 0.16);
  border-top-color: var(--vt-accent-teal);
  animation: reader-spin 0.8s linear infinite;
}

.state-icon {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  color: #b91c1c;
  background: rgba(239, 68, 68, 0.1);
  font-weight: 800;
}

@keyframes reader-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 1180px) {
  .reader-layout {
    grid-template-columns: 190px minmax(0, 1fr);
  }

  .reader-tools {
    position: static;
    grid-column: 1 / -1;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .tool-card-tutor {
    grid-column: 1 / -1;
  }
}

@media (max-width: 640px) {
  .reader-page {
    width: min(100% - 1rem, 1180px);
    padding-top: 0.85rem;
  }

  .reader-hero {
    min-height: 320px;
    padding: 2rem 1.35rem;
    align-items: flex-end;
  }

  .reader-hero h1 {
    font-size: 2.05rem;
  }

  .hero-mark {
    top: 1.5rem;
    right: 1.5rem;
    bottom: auto;
  }

  .reader-article {
    padding: 1.3rem;
  }

  .reader-tools {
    grid-template-columns: 1fr;
  }

  .reader-layout { grid-template-columns: 1fr; }
  .reader-outline { position: static; max-height: 220px; }

  .article-footer {
    align-items: stretch;
    flex-direction: column;
  }
}

@media (prefers-reduced-motion: reduce) {
  .state-spinner {
    animation: none;
  }
  .back-link {
    transition: none;
  }
}
</style>
