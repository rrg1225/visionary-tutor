<template>
  <section class="privacy-center-view">
    <header class="privacy-header">
      <div>
        <span class="vt-eyebrow">Privacy</span>
        <h1 class="vt-title">隐私中心</h1>
        <p class="vt-text-muted">查看摄像头策略、导出个人学习数据，并删除长期学习记忆。</p>
      </div>
      <button type="button" class="vt-btn vt-btn-outline" :disabled="loading" @click="loadData">
        {{ loading ? '加载中' : '刷新' }}
      </button>
    </header>

    <p v-if="error" class="privacy-error">{{ error }}</p>

    <section class="policy-grid">
      <article class="policy-cell">
        <span>摄像头默认</span>
        <strong>{{ policy.cameraDefaultEnabled ? '开启' : '关闭' }}</strong>
      </article>
      <article class="policy-cell">
        <span>原始视频上传</span>
        <strong>{{ policy.rawVideoUploaded ? '是' : '否' }}</strong>
      </article>
      <article class="policy-cell">
        <span>上传信号</span>
        <strong>{{ uploadedSignals }}</strong>
      </article>
    </section>

    <section class="memory-panel">
      <div>
        <h2>学习记忆</h2>
        <p class="vt-text-muted">当前导出中包含 {{ exportData?.memories?.length || 0 }} 条记忆和 {{ exportData?.memoryAuditLogs?.length || 0 }} 条审计日志。</p>
      </div>
      <button type="button" class="vt-btn vt-btn-outline" :disabled="deleting" @click="deleteMemory">
        {{ deleting ? '删除中' : '删除学习记忆' }}
      </button>
    </section>

    <section class="export-panel">
      <div class="export-head">
        <div>
          <h2>数据导出</h2>
          <p class="vt-text-muted">{{ exportSummary }}</p>
        </div>
        <div class="export-actions">
          <button type="button" class="vt-btn vt-btn-outline" :disabled="!exportJson" @click="copyExport">复制 JSON</button>
          <button type="button" class="vt-btn vt-btn-primary" :disabled="!exportJson" @click="downloadExport">下载 JSON</button>
        </div>
      </div>
      <textarea readonly :value="exportJson" aria-label="导出的个人数据 JSON"></textarea>
    </section>

    <section class="danger-panel">
      <div>
        <h2>注销账号</h2>
        <p class="vt-text-muted">停用登录并清除账号标识、学习画像和长期记忆。此操作不可撤销。</p>
      </div>
      <button type="button" class="vt-btn danger-btn" :disabled="deletingAccount" @click="removeAccount">
        {{ deletingAccount ? '正在注销…' : '永久注销账号' }}
      </button>
    </section>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { deleteAccount, deleteLearningMemory, fetchCameraPolicy, fetchPrivacyExport } from '../api/privacy'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/authStore'
import { useLearningSessionStore } from '../stores/learningSession'
import { useUserProfileStore } from '../stores/userProfile'
import { toastError, toastSuccess } from '../utils/toast'

const loading = ref(false)
const deleting = ref(false)
const deletingAccount = ref(false)
const error = ref('')
const exportData = ref(null)
const policy = ref({})
const router = useRouter()
const authStore = useAuthStore()
const learningSession = useLearningSessionStore()
const userProfile = useUserProfileStore()

const uploadedSignals = computed(() => (policy.value.uploadedSignals || []).join(', ') || '无')
const exportJson = computed(() => exportData.value ? JSON.stringify(exportData.value, null, 2) : '')
const exportSummary = computed(() => {
  if (!exportData.value) return '尚未加载个人数据'
  const sessions = exportData.value.sessions?.length || 0
  const resources = (exportData.value.sessions || []).reduce((sum, item) => sum + (item.artifacts?.length || 0), 0)
  return `${sessions} 个学习会话、${resources} 项资源、${exportData.value.memories?.length || 0} 条记忆、${exportData.value.learningMetrics?.length || 0} 条学习指标`
})

async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const [exportPayload, cameraPolicy] = await Promise.all([
      fetchPrivacyExport(),
      fetchCameraPolicy({ silent: true }),
    ])
    exportData.value = exportPayload
    policy.value = cameraPolicy || exportPayload?.privacyPolicy || {}
  } catch (err) {
    error.value = err?.response?.data?.message || err?.message || '隐私数据加载失败'
    toastError(error.value)
  } finally {
    loading.value = false
  }
}

async function deleteMemory() {
  if (!window.confirm('确认删除长期学习记忆和画像快照？')) return
  deleting.value = true
  try {
    const result = await deleteLearningMemory()
    toastSuccess(`已删除 ${result.activeMemoriesDeleted || 0} 条活跃记忆`)
    await loadData()
  } catch (err) {
    toastError(err?.response?.data?.message || err?.message || '删除失败')
  } finally {
    deleting.value = false
  }
}

async function copyExport() {
  if (!exportJson.value) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(exportJson.value)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = exportJson.value
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      textarea.remove()
    }
    toastSuccess('个人数据 JSON 已复制')
  } catch (err) {
    toastError(err?.message || '复制失败，请使用下载 JSON')
  }
}

function downloadExport() {
  if (!exportJson.value) return
  const blob = new Blob([exportJson.value], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `visionary-tutor-data-${new Date().toISOString().slice(0, 10)}.json`
  link.click()
  URL.revokeObjectURL(url)
}

async function removeAccount() {
  if (!window.confirm('确认永久注销账号？账号标识、画像和长期记忆将被清除，且无法恢复。')) return
  deletingAccount.value = true
  const userId = Number(authStore.currentUserId)
  try {
    await deleteAccount()
    await learningSession.clearUserState(userId)
    userProfile.$reset()
    await authStore.logout()
    toastSuccess('账号已注销')
    await router.replace('/')
  } catch (err) {
    toastError(err?.response?.data?.message || err?.message || '账号注销失败')
  } finally {
    deletingAccount.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.privacy-center-view {
  max-width: 960px;
  margin: 0 auto;
  padding: 24px 16px 48px;
  display: grid;
  gap: 16px;
}

.privacy-header,
.memory-panel {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.policy-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}

.policy-cell,
.memory-panel,
.export-panel,
.danger-panel,
.privacy-error {
  border: 1px solid var(--vt-border-subtle);
  border-radius: 8px;
  background: var(--vt-surface);
}

.policy-cell {
  padding: 14px;
  display: grid;
  gap: 4px;
}

.policy-cell span {
  color: var(--vt-text-muted);
  font-size: 13px;
}

.policy-cell strong {
  font-size: 18px;
}

.memory-panel,
.export-panel,
.danger-panel {
  padding: 16px;
}

.memory-panel h2,
.export-panel h2,
.danger-panel h2 {
  margin: 0 0 8px;
}

.export-head,
.danger-panel {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.export-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.danger-panel {
  border-color: #fecaca;
  background: #fff7f7;
}

.danger-btn {
  background: #b91c1c;
  color: white;
  border-color: #b91c1c;
}

.export-panel textarea {
  width: 100%;
  min-height: 420px;
  resize: vertical;
  padding: 12px;
  border: 1px solid var(--vt-border-subtle);
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.5;
}

.privacy-error {
  padding: 12px;
  color: #b91c1c;
}

@media (max-width: 760px) {
  .privacy-header,
  .memory-panel {
    display: grid;
  }

  .export-head,
  .danger-panel {
    display: grid;
  }

  .policy-grid {
    grid-template-columns: 1fr;
  }
}
</style>
