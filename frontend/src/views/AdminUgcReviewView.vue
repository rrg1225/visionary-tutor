<template>
  <div class="admin-review-view">
    <header class="vt-card review-header">
      <div>
        <span class="vt-eyebrow">UGC 审核</span>
        <h1 class="vt-title">待审教材队列</h1>
        <p class="vt-text-muted">审核通过后教材将进入公共库并纳入 RAG 检索。</p>
      </div>
      <button type="button" class="vt-btn vt-btn-outline" :disabled="loading" @click="loadPending">
        刷新
      </button>
    </header>

    <div v-if="loading" class="vt-text-muted">加载中…</div>
    <div v-else-if="!pending.length" class="empty-state vt-card">
      <p>暂无待审核投稿。</p>
    </div>

    <section v-else class="pending-list">
      <article v-for="book in pending" :key="book.id" class="pending-card vt-card">
        <div class="pending-meta">
          <span class="tag">{{ book.subjectTag || 'general' }}</span>
          <span class="status">待审核</span>
        </div>
        <h3>{{ book.title }}</h3>
        <p v-if="book.description" class="vt-text-muted">{{ book.description }}</p>
        <div class="pending-actions">
          <button type="button" class="vt-btn vt-btn-outline vt-btn-sm" @click="openReview(book.id)">
            审核详情
          </button>
        </div>
      </article>
    </section>

    <dialog v-if="activeBook" class="review-modal vt-card" open @click.self="closeReview">
      <header class="modal-head">
        <h2>{{ activeBook.title }}</h2>
        <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm" @click="closeReview">关闭</button>
      </header>
      <section class="provenance-review">
        <strong>来源与版权核验</strong>
        <dl>
          <dt>来源类型</dt><dd>{{ sourceTypeLabel(activeBook.sourceType) }}</dd>
          <dt>原始资料</dt><dd>{{ activeBook.sourceTitle || '未填写' }}</dd>
          <dt>原始链接</dt><dd>{{ activeBook.sourceUrl || '未填写' }}</dd>
          <dt>许可/授权</dt><dd>{{ activeBook.licenseName || '未填写' }}</dd>
          <dt>权利声明</dt><dd>{{ activeBook.rightsStatement || '未填写' }}</dd>
          <dt>投稿人确认</dt><dd>{{ activeBook.rightsConfirmed ? '已确认' : '未确认（不可通过）' }}</dd>
        </dl>
      </section>
      <div class="markdown-body" v-html="renderMarkdown(activeBook.contentMarkdown)" />
      <footer class="modal-foot">
        <button type="button" class="vt-btn vt-btn-primary vt-btn-sm" :disabled="acting" @click="handleApprove">
          通过
        </button>
        <input
          v-model="rejectReason"
          type="text"
          class="reject-input"
          placeholder="驳回原因（驳回时必填）"
        />
        <button type="button" class="vt-btn vt-btn-outline vt-btn-sm" :disabled="acting" @click="handleReject">
          驳回
        </button>
      </footer>
      <p v-if="actionMessage" class="action-msg">{{ actionMessage }}</p>
    </dialog>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import {
  approveTextbook,
  getTextbookForReview,
  listPendingTextbooks,
  rejectTextbook,
} from '../api/library'
import { renderSimpleMarkdown } from '../utils/simpleMarkdown'

const pending = ref([])
const loading = ref(true)
const activeBook = ref(null)
const rejectReason = ref('')
const acting = ref(false)
const actionMessage = ref('')

function renderMarkdown(content) {
  return renderSimpleMarkdown(content || '')
}

function sourceTypeLabel(type) {
  return {
    original: '本人原创',
    personal_notes: '整理笔记',
    open_license: '开放许可',
    authorized: '已获授权',
    legacy_import: '历史导入',
  }[type] || '未知来源'
}

async function loadPending() {
  loading.value = true
  try {
    pending.value = await listPendingTextbooks()
  } catch (err) {
    pending.value = []
    actionMessage.value = err?.response?.data?.message || err?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function openReview(id) {
  actionMessage.value = ''
  rejectReason.value = ''
  try {
    activeBook.value = await getTextbookForReview(id)
  } catch (err) {
    actionMessage.value = err?.response?.data?.message || err?.message || '加载详情失败'
  }
}

function closeReview() {
  activeBook.value = null
  rejectReason.value = ''
}

async function handleApprove() {
  if (!activeBook.value) return
  acting.value = true
  actionMessage.value = ''
  try {
    await approveTextbook(activeBook.value.id)
    actionMessage.value = '已通过审核'
    closeReview()
    await loadPending()
  } catch (err) {
    actionMessage.value = err?.response?.data?.message || err?.message || '操作失败'
  } finally {
    acting.value = false
  }
}

async function handleReject() {
  if (!activeBook.value) return
  if (!rejectReason.value.trim()) {
    actionMessage.value = '请填写驳回原因'
    return
  }
  acting.value = true
  actionMessage.value = ''
  try {
    await rejectTextbook(activeBook.value.id, rejectReason.value.trim())
    actionMessage.value = '已驳回'
    closeReview()
    await loadPending()
  } catch (err) {
    actionMessage.value = err?.response?.data?.message || err?.message || '操作失败'
  } finally {
    acting.value = false
  }
}

onMounted(loadPending)
</script>

<style scoped>
.admin-review-view {
  max-width: 960px;
  margin: 0 auto;
  padding: 1.5rem 1rem 3rem;
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.review-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 1.25rem;
}

.pending-list {
  display: grid;
  gap: 0.75rem;
}

.pending-card {
  padding: 1rem 1.25rem;
}

.pending-meta {
  display: flex;
  gap: 0.75rem;
  font-size: 0.85rem;
  color: var(--vt-muted, #64748b);
  margin-bottom: 0.35rem;
}

.tag {
  background: rgba(99, 102, 241, 0.12);
  padding: 0.1rem 0.5rem;
  border-radius: 999px;
}

.status {
  color: #d97706;
}

.review-modal {
  position: fixed;
  inset: 5vh 1rem;
  max-width: 900px;
  margin: 0 auto;
  z-index: 100;
  padding: 1rem 1.25rem;
  overflow: auto;
  border: none;
  max-height: 90vh;
}

.modal-head,
.modal-foot {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex-wrap: wrap;
}

.modal-head {
  justify-content: space-between;
  margin-bottom: 1rem;
}

.reject-input {
  flex: 1;
  min-width: 200px;
  padding: 0.45rem 0.65rem;
  border: 1px solid rgba(148, 163, 184, 0.4);
  border-radius: 8px;
}

.provenance-review {
  margin-bottom: 1rem;
  padding: 1rem;
  border: 1px solid rgba(217, 119, 6, 0.28);
  border-radius: var(--vt-radius-md);
  background: rgba(245, 158, 11, 0.06);
}

.provenance-review dl {
  display: grid;
  grid-template-columns: 6rem minmax(0, 1fr);
  gap: 0.35rem 0.75rem;
  margin: 0.75rem 0 0;
  font-size: 0.86rem;
}

.provenance-review dt {
  color: var(--vt-text-tertiary);
}

.provenance-review dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.empty-state {
  padding: 2rem;
  text-align: center;
}

.action-msg {
  margin-top: 0.75rem;
  font-size: 0.9rem;
}
</style>
