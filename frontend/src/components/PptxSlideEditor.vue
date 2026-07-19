<template>
  <Teleport to="body">
    <div v-if="open" class="editor-backdrop" role="presentation" @click.self="emit('close')">
      <section class="slide-editor vt-card" role="dialog" aria-modal="true" aria-label="PPT 幻灯片编辑器">
        <header class="editor-head">
          <div>
            <span class="vt-eyebrow">PPT 预览与编辑</span>
            <h2>确认每一页，再重新导出</h2>
          </div>
          <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm" @click="emit('close')">关闭</button>
        </header>

        <div class="deck-fields">
          <label>文件标题<input v-model="deckTitle" maxlength="255" /></label>
          <label>副标题<input v-model="subtitle" maxlength="500" /></label>
        </div>

        <div class="editor-layout">
          <aside class="thumbnail-column" aria-label="幻灯片缩略图">
            <button
              v-for="(slide, index) in slides"
              :key="slide.id"
              type="button"
              class="slide-thumbnail"
              :class="{ active: index === activeIndex }"
              @click="activeIndex = index"
            >
              <span class="slide-number">{{ index + 1 }}</span>
              <strong>{{ slide.title || '未命名页面' }}</strong>
              <small>{{ preview(slide.body) }}</small>
            </button>
            <button type="button" class="add-slide" @click="addSlide">＋ 新增页面</button>
          </aside>

          <main v-if="activeSlide" class="slide-canvas">
            <div class="canvas-toolbar">
              <span>第 {{ activeIndex + 1 }} / {{ slides.length }} 页</span>
              <div>
                <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm" :disabled="activeIndex === 0" @click="moveSlide(-1)">上移</button>
                <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm" :disabled="activeIndex === slides.length - 1" @click="moveSlide(1)">下移</button>
                <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm danger" :disabled="slides.length <= 1" @click="removeSlide">删除</button>
              </div>
            </div>
            <div class="slide-preview">
              <span>VISIONARY TUTOR</span>
              <input v-model="activeSlide.title" maxlength="255" aria-label="幻灯片标题" />
              <textarea v-model="activeSlide.body" rows="14" maxlength="3500" aria-label="幻灯片正文"></textarea>
            </div>
            <p class="editor-tip">这是结构预览，字体和分页会由导出器自动适配；正文建议每页不超过 8—12 个要点。</p>
          </main>
        </div>

        <footer class="editor-foot">
          <p v-if="message" role="status">{{ message }}</p>
          <span v-else>共 {{ slides.length }} 页内容＋1 页封面</span>
          <button type="button" class="vt-btn vt-btn-primary" :disabled="exporting || !canExport" @click="exportDeck">
            {{ exporting ? '正在重新生成…' : '按当前内容重新导出 PPTX' }}
          </button>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { downloadEditedSessionPptx } from '../api/resources'

const props = defineProps({
  open: { type: Boolean, default: false },
  sessionId: { type: [Number, String], default: null },
  resources: { type: Array, default: () => [] },
})
const emit = defineEmits(['close'])

const deckTitle = ref('个性化学习资源包')
const subtitle = ref('智眸学伴 · 多智能体个性化教学')
const slides = ref([])
const activeIndex = ref(0)
const exporting = ref(false)
const message = ref('')
let nextId = 1

const activeSlide = computed(() => slides.value[activeIndex.value] || null)
const canExport = computed(() => Boolean(
  props.sessionId
  && deckTitle.value.trim()
  && slides.value.length
  && slides.value.every((slide) => slide.title.trim() && slide.body.trim()),
))

function preview(value) {
  const text = String(value || '').replace(/\s+/g, ' ').trim()
  return text.length > 62 ? `${text.slice(0, 62)}…` : text || '暂无正文'
}

function stripHtml(value) {
  return String(value || '')
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
    .replace(/```[a-z]*|```/gi, '')
    .trim()
}

function slidesFromResource(resource) {
  if (resource.artifactType === 'VIDEO_SCRIPT' || resource.artifactType === 'VISUALIZATION') return []
  const raw = stripHtml(resource.content || resource.summary || '')
  if (!raw) return []
  const sections = raw.split(/(?=^#{1,3}\s+)/m).filter((part) => part.trim())
  return sections.slice(0, 8).map((section, sectionIndex) => {
    const heading = section.match(/^#{1,3}\s+(.+)$/m)?.[1]?.trim()
    const body = section.replace(/^#{1,3}\s+.+$/m, '').trim() || resource.summary || '请补充本页正文。'
    return {
      id: nextId++,
      title: (heading || (sectionIndex ? `${resource.title}（续）` : resource.title) || '学习内容').slice(0, 255),
      body: body.slice(0, 3500),
    }
  })
}

function resetEditor() {
  const personal = props.resources.filter((item) => !item.isShowcase && item.publishStatus !== 'BLOCKED')
  deckTitle.value = personal[0]?.sessionTopic || personal[0]?.title || '个性化学习资源包'
  slides.value = personal.flatMap(slidesFromResource).slice(0, 30)
  if (!slides.value.length) {
    slides.value = [{ id: nextId++, title: '学习内容', body: '请在这里填写本页正文。' }]
  }
  activeIndex.value = 0
  message.value = ''
}

function addSlide() {
  slides.value.push({ id: nextId++, title: '新页面', body: '请在这里填写本页正文。' })
  activeIndex.value = slides.value.length - 1
}

function removeSlide() {
  if (slides.value.length <= 1) return
  slides.value.splice(activeIndex.value, 1)
  activeIndex.value = Math.min(activeIndex.value, slides.value.length - 1)
}

function moveSlide(offset) {
  const target = activeIndex.value + offset
  if (target < 0 || target >= slides.value.length) return
  const current = slides.value[activeIndex.value]
  slides.value.splice(activeIndex.value, 1)
  slides.value.splice(target, 0, current)
  activeIndex.value = target
}

async function exportDeck() {
  if (!canExport.value || exporting.value) return
  exporting.value = true
  message.value = '正在根据编辑后的标题和正文重新生成…'
  try {
    const blob = await downloadEditedSessionPptx(props.sessionId, {
      deckTitle: deckTitle.value.trim(),
      subtitle: subtitle.value.trim(),
      slides: slides.value.map(({ title, body }) => ({ title: title.trim(), body: body.trim() })),
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `visionary-edited-session-${props.sessionId}.pptx`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    message.value = '编辑版 PPTX 已生成。'
  } catch (error) {
    message.value = error?.message || '编辑版 PPTX 导出失败，请稍后重试。'
  } finally {
    exporting.value = false
  }
}

watch(() => props.open, (value) => {
  if (value) resetEditor()
})
</script>

<style scoped>
.editor-backdrop{position:fixed;inset:0;z-index:1200;display:grid;place-items:center;padding:2vh 1rem;background:rgba(15,23,42,.58);backdrop-filter:blur(5px)}
.slide-editor{width:min(1180px,100%);max-height:96vh;display:grid;grid-template-rows:auto auto minmax(0,1fr) auto;gap:var(--vt-space-3);padding:var(--vt-space-4);overflow:hidden;background:var(--vt-bg-primary)}
.editor-head,.editor-foot,.canvas-toolbar{display:flex;align-items:center;justify-content:space-between;gap:var(--vt-space-3)}
.editor-head h2{margin:.25rem 0 0;font-size:var(--vt-text-xl)}
.deck-fields{display:grid;grid-template-columns:1fr 1fr;gap:var(--vt-space-3)}
.deck-fields label{display:grid;gap:4px;color:var(--vt-text-secondary);font-size:var(--vt-text-xs)}
.deck-fields input,.slide-preview input,.slide-preview textarea{width:100%;border:1px solid var(--vt-border-light);border-radius:var(--vt-radius-md);background:white;color:#0f172a;font:inherit}
.deck-fields input{padding:9px 11px}.editor-layout{min-height:0;display:grid;grid-template-columns:250px minmax(0,1fr);gap:var(--vt-space-4)}
.thumbnail-column{display:grid;align-content:start;gap:8px;padding-right:6px;overflow:auto}.slide-thumbnail,.add-slide{width:100%;border:1px solid var(--vt-border-light);border-radius:10px;background:var(--vt-bg-secondary);cursor:pointer;text-align:left}.slide-thumbnail{position:relative;min-height:108px;display:grid;align-content:center;gap:5px;padding:14px 12px 12px 34px}.slide-thumbnail.active{border-color:#0d9488;box-shadow:0 0 0 2px rgba(13,148,136,.12);background:white}.slide-thumbnail strong{font-size:12px}.slide-thumbnail small{color:var(--vt-text-tertiary);line-height:1.35}.slide-number{position:absolute;left:10px;top:10px;color:#0f766e;font-size:10px;font-weight:800}.add-slide{padding:10px;text-align:center;color:#0f766e}
.slide-canvas{min-width:0;display:grid;grid-template-rows:auto minmax(0,1fr) auto;gap:10px}.canvas-toolbar{font-size:var(--vt-text-xs);color:var(--vt-text-secondary)}.canvas-toolbar>div{display:flex;gap:4px}.danger{color:#b91c1c}.slide-preview{aspect-ratio:16/9;min-height:0;display:grid;grid-template-rows:auto auto minmax(0,1fr);gap:14px;padding:clamp(24px,4vw,54px);border-radius:14px;background:linear-gradient(145deg,#f8fafc,#ecfeff);box-shadow:0 18px 45px rgba(15,23,42,.13)}.slide-preview>span{color:#0f766e;font-size:10px;font-weight:800;letter-spacing:.14em}.slide-preview input{padding:10px 12px;border:0;border-bottom:2px solid rgba(13,148,136,.3);border-radius:0;background:transparent;font-size:clamp(20px,3vw,32px);font-weight:800}.slide-preview textarea{min-height:0;padding:13px;resize:none;line-height:1.65}.editor-tip,.editor-foot p,.editor-foot span{margin:0;color:var(--vt-text-tertiary);font-size:var(--vt-text-xs)}
@media(max-width:760px){.slide-editor{max-height:98vh;padding:12px}.deck-fields{grid-template-columns:1fr}.editor-layout{grid-template-columns:1fr;overflow:auto}.thumbnail-column{grid-auto-flow:column;grid-auto-columns:180px;overflow:auto}.slide-thumbnail{min-height:92px}.slide-preview{aspect-ratio:auto;min-height:430px}.editor-foot{align-items:stretch;flex-direction:column}.editor-foot button{width:100%}}
</style>
