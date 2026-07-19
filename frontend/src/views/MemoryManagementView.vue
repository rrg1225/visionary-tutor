<template>
  <section class="memory-view vt-card vt-container-narrow">
    <header class="page-header">
      <span class="vt-eyebrow">记忆管理</span>
      <h1 class="vt-title">学习记忆审查</h1>
      <p class="vt-text-muted">
        查看、编辑并审查系统从对话与学习中提取的记忆。手动修改优先级最高。
      </p>
    </header>

    <div v-if="loading" class="vt-text-muted">加载记忆中…</div>
    <p v-if="error" class="memory-error">{{ error }}</p>

    <section v-if="pending.length" class="memory-section vt-card" aria-label="待审查记忆">
      <header>
        <strong>待审查 ({{ pending.length }})</strong>
        <p class="vt-text-muted">MemoryReviewAgent 尚未通过审查的更新</p>
      </header>
      <article v-for="item in pending" :key="`pending-${item.id}`" class="memory-item pending">
        <div class="memory-meta">
          <span class="tag">{{ item.memoryType }}</span>
          <span class="tag muted">{{ item.memoryKey }}</span>
          <span v-if="item.confidenceScore != null" class="tag score">置信 {{ item.confidenceScore }}</span>
        </div>
        <p class="memory-value">{{ item.memoryValue }}</p>
        <div class="memory-actions">
          <button type="button" class="vt-btn vt-btn-primary vt-btn-sm" @click="approve(item.id)">通过</button>
          <button type="button" class="vt-btn vt-btn-outline vt-btn-sm" @click="reject(item.id)">拒绝</button>
        </div>
      </article>
    </section>

    <section class="memory-section vt-card" aria-label="手动添加记忆">
      <header><strong>手动添加 / 修改</strong></header>
      <form class="manual-form" @submit.prevent="submitManual">
        <label>
          <span class="vt-label">类型</span>
          <select v-model="manual.memoryType" required>
            <option value="profile">画像 profile</option>
            <option value="preference">偏好 preference</option>
            <option value="weak_point">薄弱点 weak_point</option>
            <option value="goal">目标 goal</option>
            <option value="progress">进度 progress</option>
          </select>
        </label>
        <label>
          <span class="vt-label">键名</span>
          <input v-model="manual.memoryKey" type="text" placeholder="如 weakPoints" required />
        </label>
        <label class="wide">
          <span class="vt-label">内容</span>
          <textarea v-model="manual.memoryValue" rows="3" required />
        </label>
        <button type="submit" class="vt-btn vt-btn-primary" :disabled="saving">保存（最高优先级）</button>
      </form>
    </section>

    <section class="memory-section vt-card" aria-label="已生效记忆">
      <header><strong>已生效记忆 ({{ memories.length }})</strong></header>
      <article v-for="item in memories" :key="item.id" class="memory-item">
        <div class="memory-meta">
          <span class="tag">{{ item.memoryType }}</span>
          <span class="tag muted">{{ item.memoryKey }}</span>
          <span class="tag source">{{ item.sourceType }}</span>
          <span class="tag" :class="item.reviewStatus">{{ item.reviewStatus }}</span>
        </div>
        <p class="memory-value">{{ item.memoryValue }}</p>
      </article>
      <p v-if="!memories.length && !loading" class="vt-text-muted">暂无记忆，完成对话建档后会自动同步。</p>
    </section>

    <section v-if="logs.length" class="memory-section vt-card" aria-label="更新日志">
      <header><strong>审查日志</strong></header>
      <ul class="log-list">
        <li v-for="log in logs" :key="log.id">
          <span class="log-status">{{ log.updateStatus }}</span>
          <span v-if="log.agentScore != null">评分 {{ log.agentScore }}</span>
          — {{ log.newValue }}
          <small>{{ formatTime(log.createdAt) }}</small>
        </li>
      </ul>
    </section>

    <div class="memory-footer">
      <RouterLink class="vt-btn vt-btn-outline" to="/profile">返回档案</RouterLink>
      <RouterLink class="vt-btn vt-btn-primary" to="/learn">返回学习循环</RouterLink>
    </div>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import {
  approveMemory,
  fetchMemories,
  fetchMemoryLogs,
  fetchPendingMemories,
  rejectMemory,
  upsertManualMemory,
} from '../api/memory'
import { toastError, toastSuccess } from '../utils/toast'

const loading = ref(true)
const saving = ref(false)
const error = ref('')
const memories = ref([])
const pending = ref([])
const logs = ref([])

const manual = reactive({
  memoryType: 'profile',
  memoryKey: '',
  memoryValue: '',
})

async function loadAll() {
  loading.value = true
  error.value = ''
  try {
    const [active, pend, auditLogs] = await Promise.all([
      fetchMemories(),
      fetchPendingMemories(),
      fetchMemoryLogs(),
    ])
    memories.value = active
    pending.value = pend
    logs.value = auditLogs
  } catch (e) {
    error.value = e?.response?.data?.message || e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function submitManual() {
  saving.value = true
  try {
    await upsertManualMemory({ ...manual })
    toastSuccess('记忆已保存')
    manual.memoryKey = ''
    manual.memoryValue = ''
    await loadAll()
  } catch (e) {
    toastError(e?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function approve(id) {
  try {
    await approveMemory(id)
    toastSuccess('已通过审查')
    await loadAll()
  } catch (e) {
    toastError('操作失败')
  }
}

async function reject(id) {
  try {
    await rejectMemory(id)
    await loadAll()
  } catch (e) {
    toastError('操作失败')
  }
}

function formatTime(value) {
  if (!value) return ''
  return new Date(value).toLocaleString()
}

onMounted(loadAll)
</script>

<style scoped>
.memory-view {
  display: grid;
  gap: var(--vt-space-5);
  padding: var(--vt-space-6);
}

.page-header {
  display: grid;
  gap: var(--vt-space-2);
}

.memory-section {
  display: grid;
  gap: var(--vt-space-4);
  padding: var(--vt-space-4);
}

.memory-item {
  display: grid;
  gap: var(--vt-space-2);
  padding: var(--vt-space-3);
  border: 1px solid var(--vt-border-subtle);
  border-radius: var(--vt-radius-md);
}

.memory-item.pending {
  border-color: var(--vt-accent-warning, #d4a017);
}

.memory-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}

.tag {
  font-size: var(--vt-text-xs);
  padding: 2px 8px;
  border-radius: var(--vt-radius-sm);
  background: var(--vt-bg-muted);
}

.tag.muted { opacity: 0.8; }
.tag.score { background: rgba(46, 125, 50, 0.12); }
.tag.pending { color: #b8860b; }
.tag.approved { color: #2e7d32; }

.memory-value {
  margin: 0;
  line-height: 1.5;
}

.memory-actions {
  display: flex;
  gap: var(--vt-space-2);
}

.manual-form {
  display: grid;
  gap: var(--vt-space-3);
  grid-template-columns: 1fr 1fr;
}

.manual-form .wide {
  grid-column: 1 / -1;
}

.manual-form label {
  display: grid;
  gap: var(--vt-space-1);
}

.manual-form input,
.manual-form select,
.manual-form textarea {
  width: 100%;
}

.log-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: var(--vt-space-2);
}

.log-list li {
  font-size: var(--vt-text-sm);
  line-height: 1.4;
}

.log-status {
  font-weight: 600;
}

.memory-error {
  color: var(--vt-danger, #c62828);
}

.memory-footer {
  display: flex;
  gap: var(--vt-space-3);
  flex-wrap: wrap;
}
</style>
