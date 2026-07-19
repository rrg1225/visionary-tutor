<template>
  <section class="assessment-view vt-section">
    <div v-if="authStore.guestSessionError" class="guest-session-alert vt-container" role="alert">
      <p>{{ authStore.guestSessionError }}</p>
      <button
        type="button"
        class="vt-btn vt-btn-outline vt-btn-sm"
        :disabled="authStore.isLoading"
        @click="retryGuestSession"
      >
        {{ authStore.isLoading ? '连接中...' : '重试连接' }}
      </button>
    </div>

    <header class="page-header vt-container">
      <span class="vt-eyebrow">知识测评 · 作业评估</span>
      <h1 class="vt-title">上传作业，看看哪里还没掌握</h1>
      <p class="vt-text-muted page-description">
        上传作业图片、PDF/Word/PPT 或文档包，填写主题和背景，系统会给出掌握度反馈与纠错建议。
        <span v-if="authStore.isGuest" class="guest-notice">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
          </svg>
          登录后可保存评估历史
        </span>
      </p>
    </header>

    <section class="assessment-modes vt-container" aria-label="测评方式">
      <article class="vt-card active">
        <span>方式一</span>
        <strong>上传作业或学习文件</strong>
        <p>适合评估你已经完成的图片、PDF、Word、PPT、文本或代码材料。</p>
      </article>
      <article class="vt-card">
        <span>方式二</span>
        <strong>按系统内容整篇自测</strong>
        <p>不需要准备文件；选择专题书、文章、论文导读或综述，在正文末尾完成固定自测。</p>
        <RouterLink class="vt-btn vt-btn-primary vt-btn-sm" to="/library">选择系统内容</RouterLink>
      </article>
    </section>

    <div class="assessment-layout vt-container">
      <aside
        class="assessment-guide vt-card"
        :class="{ 'assessment-guide-expanded': guideExpanded }"
        aria-label="使用说明"
      >
        <button
          type="button"
          class="guide-toggle"
          :aria-expanded="guideExpanded"
          aria-controls="assessment-guide-body"
          @click="guideExpanded = !guideExpanded"
        >
          <span class="guide-heading">
            <span class="guide-kicker">3 步完成评估</span>
            <span class="guide-title">怎么用？</span>
          </span>
          <span class="guide-toggle-hint">{{ guideExpanded ? '收起说明' : '查看说明' }}</span>
          <svg class="guide-chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <path d="M6 9l6 6 6-6" />
          </svg>
        </button>

        <div id="assessment-guide-body" class="guide-body">
          <ol class="guide-steps">
          <li v-for="step in guideSteps" :key="step.id">
            <span class="step-num">{{ step.num }}</span>
            <div>
              <strong>{{ step.title }}</strong>
              <p>{{ step.desc }}</p>
            </div>
          </li>
          </ol>

        <section class="guide-block" aria-label="适合上传的内容">
          <h3>适合上传这些</h3>
          <ul class="guide-list">
            <li v-for="item in uploadTips" :key="item">{{ item }}</li>
          </ul>
        </section>

        <section class="guide-block" aria-label="快捷主题">
          <h3>快捷填主题</h3>
          <div class="topic-picks">
            <button
              v-for="topic in topicPresets"
              :key="topic.label"
              type="button"
              class="topic-pick"
              :disabled="isSubmitting"
              @click="applyTopicPreset(topic)"
            >
              {{ topic.label }}
            </button>
          </div>
        </section>

        <section class="guide-block guide-outcome" aria-label="提交后获得">
          <h3>提交后会看到</h3>
          <ul class="outcome-list">
            <li v-for="item in outcomeItems" :key="item">{{ item }}</li>
          </ul>
        </section>

          <div class="guide-links">
            <RouterLink to="/learning-report" class="guide-link">查看学习报告 →</RouterLink>
            <RouterLink :to="{ path: '/learn', query: { panel: 'diagnosis' } }" class="guide-link">打开知识诊断 →</RouterLink>
          </div>
        </div>
      </aside>

      <form class="assessment-form vt-card-elevated" @submit.prevent="handleSubmit">
      <div class="form-grid">
        <!-- Topic Field -->
        <div class="form-field">
          <label for="topic" class="vt-label vt-label-required">
            评估主题
          </label>
          <input
            id="topic"
            v-model="form.topic"
            type="text"
            class="vt-input"
            placeholder="例如：卷积神经网络练习题"
            required
            :disabled="isSubmitting"
          />
          <span class="field-hint">简要描述您希望评估的作业内容</span>
        </div>

        <!-- Context Field -->
        <div class="form-field form-field-wide">
          <label for="context" class="vt-label">
            背景说明
          </label>
          <textarea
            id="context"
            v-model="form.contextPrompt"
            rows="4"
            class="vt-textarea"
            placeholder="例如：第三题特征图尺寸算错了；或希望重点看反向传播推导"
            :disabled="isSubmitting"
          />
          <span class="field-hint">写清楚卡在哪一步，反馈会更对症</span>
        </div>

        <!-- File Upload Zone -->
        <div class="form-field form-field-wide">
          <label class="vt-label vt-label-required">作业或学习文件</label>

          <div
            class="upload-zone"
            :class="{
              'upload-active': isDragging,
              'upload-has-file': hasFile,
              'upload-error': errorMessage && !selectedFile,
            }"
            @dragenter.prevent="isDragging = true"
            @dragover.prevent
            @dragleave.prevent="isDragging = false"
            @drop.prevent="handleDrop"
            @click="triggerFileInput"
          >
            <input
              ref="fileInput"
              type="file"
              accept="image/*,.pdf,.docx,.pptx,.txt,.md,.zip,.py,.java,.js,.ts,.jsx,.tsx,.c,.h,.cpp,.hpp,.go,.rs,.html,.css,.json,.ipynb,.sql,.yaml,.yml"
              class="file-input"
              @change="handleFileSelect"
              :disabled="isSubmitting"
            />

            <!-- Empty State -->
            <div v-if="!hasFile" class="upload-placeholder">
              <div class="upload-icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                  <path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M17 8l-5-5-5 5M12 3v12" />
                </svg>
              </div>
              <div class="upload-text">
                <strong>
                  <span class="upload-copy-desktop">拖放作业或学习文件到此处</span>
                  <span class="upload-copy-mobile">拍照或选择文件</span>
                </strong>
                <span>图片≤10MB；PDF、DOCX、PPTX、TXT/MD、ZIP≤20MB</span>
              </div>
            </div>

            <!-- File Preview State -->
            <div v-else class="upload-preview">
              <div class="preview-thumbnail">
                <img v-if="previewUrl" :src="previewUrl" alt="预览" />
                <div v-else class="preview-placeholder">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                    <rect x="3" y="3" width="18" height="18" rx="2" />
                    <circle cx="8.5" cy="8.5" r="1.5" />
                    <path d="M21 15l-5-5L5 21" />
                  </svg>
                </div>
              </div>
              <div class="preview-info">
                <span class="preview-name" :title="selectedFileName">{{ selectedFileName }}</span>
                <span class="preview-size">{{ formatFileSize(selectedFile?.size) }}</span>
                <div v-if="uploadProgress > 0 && uploadProgress < 100" class="preview-progress">
                  <div class="progress-bar" :style="{ width: `${uploadProgress}%` }"></div>
                </div>
              </div>
              <button
                type="button"
                class="preview-remove vt-btn vt-btn-ghost"
                @click.stop="clearFile"
                :disabled="isSubmitting"
                aria-label="移除文件"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M18 6L6 18M6 6l12 12" />
                </svg>
              </button>
            </div>
          </div>
        </div>

        <!-- Alerts -->
        <Transition name="alert">
          <div v-if="errorMessage" class="form-alert vt-form-error" role="alert">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <circle cx="12" cy="12" r="10" />
              <path d="M12 8v4M12 16h.01" />
            </svg>
            <span>{{ errorMessage }}</span>
          </div>
        </Transition>

        <Transition name="alert">
          <div v-if="statusMessage" class="form-alert vt-form-success" role="status">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M22 11.08V12a10 10 0 11-5.93-9.14" />
              <polyline points="22 4 12 14.01 9 11.01" />
            </svg>
            <span>{{ statusMessage }}</span>
          </div>
        </Transition>

        <!-- Form Actions -->
        <div class="form-actions form-field-wide">
          <button
            type="button"
            class="vt-btn vt-btn-ghost demo-load-btn"
            :disabled="isSubmitting"
            data-testid="btn-load-cnn-demo"
            @click="loadCnnDemoSample"
          >
            加载 CNN 演示样例
          </button>

          <div class="form-actions-main">
            <RouterLink class="vt-btn vt-btn-outline" to="/learn">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M19 12H5M12 19l-7-7 7-7" />
              </svg>
              返回学习循环
            </RouterLink>

            <button
              type="submit"
              class="vt-btn vt-btn-primary"
              :disabled="!canSubmit || isSubmitting"
              data-testid="btn-submit-assessment"
            >
              <svg v-if="isSubmitting" class="btn-spinner" width="16" height="16" viewBox="0 0 24 24" data-testid="skeleton-assessment-submit">
                <circle cx="12" cy="12" r="10" fill="none" stroke="currentColor" stroke-width="3" stroke-dasharray="31.416" stroke-dashoffset="10"/>
              </svg>
              <span>{{ isSubmitting ? '评估处理中...' : (lastSubmissionFailed ? '重新提交评估' : '提交评估') }}</span>
              <svg v-if="!isSubmitting" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M5 12h14M12 5l7 7-7 7" />
              </svg>
            </button>
          </div>
        </div>
      </div>
      </form>
    </div>

    <div class="info-cards vt-container">
      <article v-for="card in infoCards" :key="card.title" class="info-card vt-card">
        <span class="info-emoji" aria-hidden="true">{{ card.icon }}</span>
        <h3>{{ card.title }}</h3>
        <p>{{ card.desc }}</p>
      </article>
    </div>

    <section class="related-strip vt-container vt-card" aria-label="测评之后可以做什么">
      <h2>测评之后</h2>
      <div class="related-grid">
        <RouterLink
          v-for="item in relatedActions"
          :key="item.label"
          :to="item.to"
          class="related-card"
        >
          <span class="related-icon" aria-hidden="true">{{ item.icon }}</span>
          <div>
            <strong>{{ item.label }}</strong>
            <p>{{ item.desc }}</p>
          </div>
        </RouterLink>
      </div>
    </section>
  </section>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import { useAssessmentUpload } from '../composables/useAssessmentUpload.js'
import { useAuthStore } from '../stores/authStore.js'

const router = useRouter()
const authStore = useAuthStore()

const fileInput = ref(null)
const previewUrl = ref('')
const guideExpanded = ref(
  typeof window !== 'undefined' && window.matchMedia('(min-width: 960px)').matches,
)

const guideSteps = [
  { id: '1', num: '1', title: '填主题', desc: '说明是哪一章、哪类题' },
  { id: '2', num: '2', title: '上传文件', desc: '图片、PDF、Word、PPT 或文档包均可' },
  { id: '3', num: '3', title: '查看反馈', desc: '掌握度与薄弱点在学习循环里看' },
]

const uploadTips = [
  '手写公式与推导过程',
  '编程题截图或报错信息',
  '选择题填涂与草稿',
  '实验报告中的关键步骤',
  'PDF、Word/PPT 学习作业或 TXT/Markdown 笔记',
  '包含多份文档的 ZIP（最多 30 个文件）',
]

const topicPresets = [
  { label: 'CNN 卷积', topic: '卷积神经网络', context: '重点关注特征图尺寸与 padding 计算。' },
  { label: '反向传播', topic: '反向传播推导', context: '请检查链式法则与梯度计算步骤。' },
  { label: '目标检测', topic: '目标检测', context: '关注 IoU、锚框与 NMS 相关题目。' },
  { label: 'PyTorch 实操', topic: 'PyTorch 编程', context: '请重点看 tensor shape 与维度匹配。' },
]

const outcomeItems = [
  '掌握度与错误模式摘要',
  '薄弱知识点定位',
  '针对性补救建议',
  '同步到学习循环测评面板',
]

const infoCards = [
  { icon: '⏱️', title: '耐心等待', desc: '通常需要 30–90 秒，复杂图片或长文档可能更久' },
  { icon: '🔒', title: '隐私保护', desc: '文件仅用于评估流程，不会自动进入公共教材库' },
  { icon: '📄', title: '格式支持', desc: '图片、PDF、DOCX、PPTX、TXT/MD、ZIP' },
  { icon: '📊', title: '报告联动', desc: '结果写入学习报告与知识诊断' },
]

const relatedActions = [
  {
    icon: '📈',
    label: '学习报告',
    desc: '查看掌握度雷达与改进建议',
    to: '/learning-report',
  },
  {
    icon: '🔍',
    label: '知识诊断',
    desc: '在薄弱节点图谱里继续深挖',
    to: { path: '/learn', query: { panel: 'diagnosis' } },
  },
  {
    icon: '📚',
    label: '生成补救资料',
    desc: '针对薄弱点勾选讲义或习题',
    to: '/resources',
  },
  {
    icon: '💬',
    label: '继续提问',
    desc: '带着测评结果回对话区追问',
    to: '/learn',
  },
]

function applyTopicPreset(preset) {
  form.topic = preset.topic
  form.contextPrompt = preset.context
  clearMessages()
}

// Use the composable for upload logic
const {
  isDragging,
  selectedFile,
  selectedFileName,
  isSubmitting,
  errorMessage,
  statusMessage,
  uploadProgress,
  lastSubmissionFailed,
  form,
  canSubmit,
  hasFile,
  handleFileSelect,
  handleDrop,
  clearFile,
  selectFile,
  clearMessages,
  submitAssessment,
} = useAssessmentUpload({
  onSuccess: () => {
    setTimeout(() => {
      router.push({ path: '/learn', query: { panel: 'assessment' } })
    }, 1200)
  },
  onError: (error) => {
    console.error('Assessment submission failed:', error)
  },
})

function triggerFileInput() {
  if (!isSubmitting.value) {
    fileInput.value?.click()
  }
}

function formatFileSize(bytes) {
  if (!bytes) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

watch(selectedFile, (file) => {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
  if (file?.type?.startsWith('image/')) {
    previewUrl.value = URL.createObjectURL(file)
  }
})

const DEMO_IMAGE_URL = '/demo/assessment-cnn-sample.png'

async function loadCnnDemoSample() {
  if (isSubmitting.value) return

  form.topic = '卷积神经网络'
  form.contextPrompt = '演示样例：卷积层输出尺寸推导与 padding 设置，请关注特征图尺寸计算。'
  clearMessages()

  try {
    const response = await fetch(DEMO_IMAGE_URL)
    if (!response.ok) {
      throw new Error('演示图片加载失败')
    }
    const blob = await response.blob()
    const file = new File(
      [blob],
      'assessment-cnn-sample.png',
      { type: blob.type || 'image/png' },
    )
    selectFile(file)
  } catch (error) {
    console.error('Failed to load CNN demo sample:', error)
  }
}

async function retryGuestSession() {
  await authStore.retryGuestSession()
}

async function handleSubmit() {
  try {
    await submitAssessment()
  } catch (error) {
    console.error('Assessment submission failed:', error)
  }
}
</script>

<style scoped>
.assessment-modes { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: var(--vt-space-4); margin-bottom: var(--vt-space-5); }
.assessment-modes article { padding: var(--vt-space-4); display: grid; gap: var(--vt-space-2); border: 1px solid rgba(148,163,184,.22); }
.assessment-modes article.active { border-color: rgba(13,148,136,.35); background: rgba(13,148,136,.04); }
.assessment-modes span { color: var(--vt-text-tertiary); font-size: var(--vt-text-xs); }.assessment-modes p { margin: 0; color: var(--vt-text-secondary); line-height: 1.55; }
@media (max-width: 700px) { .assessment-modes { grid-template-columns: 1fr; } }
.assessment-view {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-6);
  padding-bottom: var(--vt-space-10);
}

.guest-session-alert {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-4);
  border-radius: var(--vt-radius-md);
  background: rgba(245, 158, 11, 0.1);
  border: 1px solid rgba(245, 158, 11, 0.35);
  font-size: var(--vt-text-sm);
  color: #b45309;
}

.page-header {
  margin-bottom: 0;
}

.guest-notice {
  display: inline-flex;
  align-items: center;
  gap: var(--vt-space-2);
  margin-top: var(--vt-space-2);
  font-size: var(--vt-text-sm);
  color: #f59e0b;
  background: rgba(245, 158, 11, 0.08);
  padding: var(--vt-space-2) var(--vt-space-3);
  border-radius: var(--vt-radius-md);
}

.page-description {
  max-width: 56ch;
  line-height: 1.65;
}

.assessment-layout {
  display: grid;
  gap: var(--vt-space-5);
  align-items: start;
}

@media (min-width: 960px) {
  .assessment-layout {
    grid-template-columns: minmax(260px, 300px) minmax(0, 1fr);
  }
}

.assessment-guide {
  display: grid;
  padding: var(--vt-space-5);
  position: sticky;
  top: calc(56px + var(--vt-space-4));
  overflow: hidden;
}

.guide-toggle {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: default;
}

.guide-heading {
  display: grid;
  gap: 2px;
}

.guide-kicker {
  display: none;
  font-size: 11px;
  font-weight: var(--vt-font-semibold);
  color: var(--vt-accent-teal-dark);
}

.guide-toggle-hint,
.guide-chevron {
  display: none;
}

.guide-body {
  display: grid;
  gap: var(--vt-space-4);
  margin-top: var(--vt-space-4);
}

.guide-title {
  margin: 0;
  font-size: var(--vt-text-base);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-primary);
}

.guide-steps {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--vt-space-3);
}

.guide-steps li {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--vt-space-3);
  align-items: start;
}

.step-num {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: rgba(13, 148, 136, 0.12);
  color: var(--vt-accent-teal-dark);
  font-size: 11px;
  font-weight: var(--vt-font-bold);
}

.guide-steps strong {
  display: block;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-primary);
}

.guide-steps p {
  margin: 2px 0 0;
  font-size: 10px;
  line-height: 1.45;
  color: var(--vt-text-tertiary);
}

.guide-block h3 {
  margin: 0 0 var(--vt-space-2);
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-secondary);
}

.guide-list {
  margin: 0;
  padding-left: 1.1rem;
  font-size: 11px;
  line-height: 1.55;
  color: var(--vt-text-tertiary);
}

.guide-list li + li {
  margin-top: 4px;
}

.topic-picks {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}

.topic-pick {
  padding: 4px 10px;
  border-radius: var(--vt-radius-full);
  border: 1px solid var(--vt-border-light);
  background: var(--vt-surface);
  font-size: 11px;
  color: var(--vt-text-secondary);
  cursor: pointer;
  transition: border-color var(--vt-transition-base), color var(--vt-transition-base);
}

.topic-pick:hover:not(:disabled) {
  border-color: rgba(13, 148, 136, 0.45);
  color: var(--vt-accent-teal-dark);
}

.topic-pick:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.guide-outcome {
  padding: var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
}

.outcome-list {
  margin: 0;
  padding-left: 1.1rem;
  font-size: 11px;
  line-height: 1.55;
  color: var(--vt-text-secondary);
}

.outcome-list li + li {
  margin-top: 4px;
}

.guide-links {
  display: grid;
  gap: var(--vt-space-2);
  padding-top: var(--vt-space-2);
  border-top: 1px solid var(--vt-border-light);
}

.guide-link {
  font-size: 11px;
  font-weight: var(--vt-font-medium);
  color: var(--vt-accent-teal-dark);
  text-decoration: none;
}

.guide-link:hover {
  text-decoration: underline;
}

.assessment-form {
  padding: var(--vt-space-6);
}

@media (min-width: 640px) {
  .assessment-form {
    padding: var(--vt-space-8);
  }
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--vt-space-6);
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-2);
}

.form-field-wide {
  grid-column: 1 / -1;
}

.field-hint {
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
}

.upload-zone {
  position: relative;
  min-height: 220px;
  border: 2px dashed var(--vt-border-light);
  border-radius: var(--vt-radius-lg);
  background: var(--vt-bg-secondary);
  transition: all var(--vt-transition-base);
  cursor: pointer;
  overflow: hidden;
}

.upload-zone:hover {
  border-color: var(--vt-border-medium);
  background: var(--vt-bg-tertiary);
}

.upload-active {
  border-color: var(--vt-accent-teal);
  background: rgba(13, 148, 136, 0.04);
}

.upload-has-file {
  border-color: var(--vt-accent-teal);
  background: white;
  cursor: default;
}

.file-input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.upload-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--vt-space-4);
  padding: var(--vt-space-6);
  text-align: center;
}

.upload-icon {
  width: 48px;
  height: 48px;
  color: var(--vt-text-tertiary);
}

.upload-text {
  display: grid;
  gap: var(--vt-space-1);
}

.upload-text strong {
  font-size: var(--vt-text-base);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-primary);
}

.upload-text > span {
  font-size: var(--vt-text-sm);
  color: var(--vt-text-secondary);
}

.upload-copy-mobile {
  display: none;
}

.upload-preview {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  gap: var(--vt-space-4);
  padding: var(--vt-space-4);
  background: white;
}

.preview-thumbnail {
  width: 120px;
  height: 120px;
  flex-shrink: 0;
  border-radius: var(--vt-radius-md);
  overflow: hidden;
  background: var(--vt-bg-secondary);
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview-thumbnail img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.preview-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--vt-space-2);
}

.preview-name {
  font-weight: var(--vt-font-medium);
  color: var(--vt-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preview-size {
  font-size: var(--vt-text-sm);
  color: var(--vt-text-secondary);
}

.preview-progress {
  width: 100%;
  height: 4px;
  background: var(--vt-border-light);
  border-radius: 2px;
  overflow: hidden;
}

.progress-bar {
  height: 100%;
  background: var(--vt-accent-teal);
  border-radius: 2px;
  transition: width 0.2s ease;
}

.form-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-3);
  justify-content: space-between;
  align-items: center;
  margin-top: var(--vt-space-2);
  padding-top: var(--vt-space-6);
  border-top: 1px solid var(--vt-border-light);
}

.form-actions-main {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-3);
  margin-left: auto;
}

@media (max-width: 959px) {
  .assessment-guide {
    position: static;
    top: auto;
    display: block;
    padding: 0;
    border-color: rgba(13, 148, 136, 0.16);
    background: linear-gradient(135deg, rgba(255, 255, 255, 0.98), rgba(240, 253, 250, 0.92));
  }

  .guide-toggle {
    min-height: 74px;
    padding: var(--vt-space-4) var(--vt-space-5);
    cursor: pointer;
    touch-action: manipulation;
  }

  .guide-kicker,
  .guide-toggle-hint,
  .guide-chevron {
    display: block;
  }

  .guide-toggle-hint {
    margin-left: auto;
    font-size: var(--vt-text-xs);
    color: var(--vt-text-tertiary);
  }

  .guide-chevron {
    width: 18px;
    height: 18px;
    margin-left: var(--vt-space-2);
    color: var(--vt-accent-teal-dark);
    transition: transform var(--vt-transition-base);
  }

  .assessment-guide-expanded .guide-chevron {
    transform: rotate(180deg);
  }

  .guide-body {
    display: none;
    margin: 0;
    padding: 0 var(--vt-space-5) var(--vt-space-5);
    border-top: 1px solid rgba(13, 148, 136, 0.12);
  }

  .assessment-guide-expanded .guide-body {
    display: grid;
  }

  .guide-steps {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: var(--vt-space-2);
    padding-top: var(--vt-space-4);
  }

  .guide-steps li {
    display: grid;
    grid-template-columns: 1fr;
    justify-items: start;
    gap: var(--vt-space-2);
    padding: var(--vt-space-3);
    border-radius: var(--vt-radius-md);
    background: rgba(255, 255, 255, 0.72);
  }

  .guide-steps p {
    display: none;
  }
}

@media (max-width: 639px) {
  .assessment-view {
    gap: var(--vt-space-4);
    padding-bottom: var(--vt-space-6);
  }

  .page-header {
    padding-top: var(--vt-space-2);
  }

  .page-header .vt-title {
    font-size: clamp(1.6rem, 8vw, 2.1rem);
    line-height: 1.14;
  }

  .assessment-form {
    padding: var(--vt-space-5);
    border-radius: var(--vt-radius-xl);
  }

  .form-grid {
    gap: var(--vt-space-5);
  }

  .upload-zone {
    min-height: 168px;
    border-width: 1.5px;
  }

  .upload-placeholder {
    gap: var(--vt-space-3);
    padding: var(--vt-space-4);
  }

  .upload-icon {
    width: 40px;
    height: 40px;
  }

  .upload-copy-desktop {
    display: none;
  }

  .upload-copy-mobile {
    display: inline;
    color: var(--vt-text-primary) !important;
  }

  .upload-text > span {
    font-size: var(--vt-text-xs);
  }

  .preview-thumbnail {
    width: 92px;
    height: 92px;
  }

  .form-actions {
    display: grid;
    grid-template-columns: 1fr;
    padding-top: var(--vt-space-5);
  }

  .demo-load-btn,
  .form-actions-main,
  .form-actions-main .vt-btn {
    width: 100%;
  }

  .form-actions-main {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr);
    margin-left: 0;
  }

  .form-actions .vt-btn {
    min-height: 48px;
    gap: var(--vt-space-2);
    padding-inline: var(--vt-space-2);
    font-size: 13px;
    white-space: nowrap;
  }

}

.info-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--vt-space-4);
}

@media (min-width: 768px) {
  .info-cards {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 639px) {
  .info-cards {
    grid-template-columns: 1fr;
  }
}

.info-card {
  display: grid;
  gap: var(--vt-space-2);
  padding: var(--vt-space-5);
  text-align: center;
  justify-items: center;
}

.info-emoji {
  font-size: 1.5rem;
  line-height: 1;
}

.info-card h3 {
  margin: 0;
  font-size: var(--vt-text-sm);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-primary);
}

.info-card p {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--vt-text-tertiary);
}

.related-strip {
  padding: var(--vt-space-5);
}

.related-strip h2 {
  margin: 0 0 var(--vt-space-4);
  font-size: var(--vt-text-base);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-primary);
}

.related-grid {
  display: grid;
  gap: var(--vt-space-3);
}

@media (min-width: 640px) {
  .related-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (min-width: 960px) {
  .related-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

.related-card {
  display: flex;
  gap: var(--vt-space-3);
  padding: var(--vt-space-4);
  border-radius: var(--vt-radius-md);
  border: 1px solid var(--vt-border-light);
  background: var(--vt-surface);
  text-decoration: none;
  color: inherit;
  transition: border-color var(--vt-transition-base), background var(--vt-transition-base);
}

.related-card:hover {
  border-color: rgba(13, 148, 136, 0.4);
  background: rgba(13, 148, 136, 0.04);
}

.related-icon {
  font-size: 1.25rem;
  line-height: 1;
  flex-shrink: 0;
}

.related-card strong {
  display: block;
  font-size: var(--vt-text-xs);
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-primary);
}

.related-card p {
  margin: 4px 0 0;
  font-size: 10px;
  line-height: 1.45;
  color: var(--vt-text-tertiary);
}

.btn-spinner {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
