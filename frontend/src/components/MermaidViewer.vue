<template>
  <div class="mermaid-viewer">
    <div v-if="rendered" class="diagram-actions" aria-label="导图下载">
      <span>可保存为图片</span>
      <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm" @click="downloadSvg">下载 SVG</button>
      <button type="button" class="vt-btn vt-btn-outline vt-btn-sm" :disabled="pngBusy" @click="downloadPng">
        {{ pngBusy ? '生成中…' : '下载 PNG' }}
      </button>
    </div>
    <div v-if="error" class="mermaid-error">{{ error }}</div>
    <div v-else ref="container" class="mermaid-container" aria-label="思维导图"></div>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref, watch } from 'vue'

let mermaidLib = null
let mermaidReady = null

async function getMermaid() {
  if (mermaidLib) return mermaidLib
  if (!mermaidReady) {
    mermaidReady = (async () => {
      const mermaid = await import('mermaid')
      const lib = mermaid.default
      lib.initialize({
        startOnLoad: false,
        theme: 'neutral',
        securityLevel: 'strict',
      })
      mermaidLib = lib
      return lib
    })()
  }
  return mermaidReady
}

const props = defineProps({
  content: { type: String, default: '' },
})

const container = ref(null)
const error = ref('')
const rendered = ref(false)
const pngBusy = ref(false)

function extractMermaidSource(content = '') {
  const fenced = content.match(/```mermaid\s*([\s\S]*?)```/i)
  if (fenced) return fenced[1].trim()
  if (/^(mindmap|graph|flowchart|sequenceDiagram)/im.test(content.trim())) {
    return content.trim()
  }
  return ''
}

async function renderDiagram() {
  error.value = ''
  rendered.value = false
  const source = extractMermaidSource(props.content)
  if (!source) {
    error.value = '未检测到 Mermaid 导图语法'
    return
  }
  if (!container.value) return

  try {
    const mermaid = await getMermaid()
    const id = `mermaid-${Date.now()}`
    const { svg } = await mermaid.render(id, source)
    container.value.innerHTML = svg
    rendered.value = true
  } catch (err) {
    error.value = `导图渲染失败：${err?.message || '语法不兼容'}`
    if (container.value) container.value.innerHTML = ''
  }
}

function currentSvg() {
  return container.value?.querySelector('svg') || null
}

function serializeSvg(svg) {
  const clone = svg.cloneNode(true)
  clone.setAttribute('xmlns', 'http://www.w3.org/2000/svg')
  return `<?xml version="1.0" encoding="UTF-8"?>\n${new XMLSerializer().serializeToString(clone)}`
}

function triggerDownload(blob, filename) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}

function downloadSvg() {
  const svg = currentSvg()
  if (!svg) return
  triggerDownload(new Blob([serializeSvg(svg)], { type: 'image/svg+xml;charset=utf-8' }), 'visionary-diagram.svg')
}

async function downloadPng() {
  const svg = currentSvg()
  if (!svg || pngBusy.value) return
  pngBusy.value = true
  const source = serializeSvg(svg)
  const blobUrl = URL.createObjectURL(new Blob([source], { type: 'image/svg+xml;charset=utf-8' }))
  try {
    const image = new Image()
    await new Promise((resolve, reject) => {
      image.onload = resolve
      image.onerror = reject
      image.src = blobUrl
    })
    const box = svg.viewBox?.baseVal
    const width = Math.max(1, Math.ceil(box?.width || svg.getBoundingClientRect().width || image.naturalWidth || 1200))
    const height = Math.max(1, Math.ceil(box?.height || svg.getBoundingClientRect().height || image.naturalHeight || 800))
    const scale = Math.min(3, Math.max(2, 2400 / width))
    const canvas = document.createElement('canvas')
    canvas.width = Math.ceil(width * scale)
    canvas.height = Math.ceil(height * scale)
    const context = canvas.getContext('2d')
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, canvas.width, canvas.height)
    context.drawImage(image, 0, 0, canvas.width, canvas.height)
    const png = await new Promise((resolve, reject) => canvas.toBlob((value) => value ? resolve(value) : reject(new Error('PNG 编码失败')), 'image/png'))
    triggerDownload(png, 'visionary-diagram.png')
  } catch (downloadError) {
    error.value = `PNG 下载失败：${downloadError?.message || '浏览器不支持'}`
  } finally {
    URL.revokeObjectURL(blobUrl)
    pngBusy.value = false
  }
}

watch(() => props.content, async () => {
  await nextTick()
  renderDiagram()
})

onMounted(renderDiagram)
</script>

<style scoped>
.mermaid-viewer {
  width: 100%;
  overflow: auto;
  border-radius: var(--vt-radius-md);
  background: #fff;
  border: 1px solid var(--vt-border-light);
}

.mermaid-container {
  min-height: 200px;
  padding: var(--vt-space-2);
}

.diagram-actions {
  position: sticky;
  left: 0;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--vt-space-2);
  padding: var(--vt-space-2);
  border-bottom: 1px solid var(--vt-border-light);
  background: rgba(248, 250, 252, 0.96);
}

.diagram-actions span {
  margin-right: auto;
  color: var(--vt-text-tertiary);
  font-size: 10px;
}

.mermaid-container :deep(svg) {
  max-width: 100%;
  height: auto;
}

.mermaid-error {
  padding: var(--vt-space-3);
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
}
</style>
