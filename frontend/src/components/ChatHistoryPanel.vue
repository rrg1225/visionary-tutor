<template>
  <section class="chat-history-panel" :class="{ open: expanded }">
    <button
      type="button"
      class="history-toggle"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <span class="history-toggle-label">历史会话</span>
      <span v-if="currentTopic" class="history-current">{{ currentTopic }}</span>
      <span class="history-count">{{ summaries.length }} 条</span>
      <span class="history-chevron" aria-hidden="true">{{ expanded ? '▾' : '▸' }}</span>
    </button>

    <div v-if="expanded" class="history-body">
      <div class="history-actions">
        <button
          type="button"
          class="vt-btn vt-btn-outline vt-btn-sm"
          :disabled="busy || !authStore.isRegistered"
          @click="handleNewSession"
        >
          新建会话
        </button>
        <span v-if="!authStore.isRegistered" class="history-hint">登录后可云端保存聊天记录</span>
      </div>

      <div v-if="learningSession.sessionsLoading" class="history-empty">加载中…</div>
      <div v-else-if="!summaries.length" class="history-empty">暂无历史会话，发送第一条消息后会自动创建。</div>
      <ul v-else class="history-list" role="list">
        <li
          v-for="item in summaries"
          :key="item.id"
          class="history-item"
          :class="{ active: item.id === learningSession.currentSessionId }"
        >
          <button type="button" class="history-select" @click="handleSelect(item.id)">
            <span class="history-item-topic">{{ item.topic || '未命名会话' }}</span>
            <span class="history-item-meta">
              {{ formatDate(item.gmtModified || item.gmtCreated) }}
              · {{ item.messageCount || 0 }} 条消息
            </span>
            <span v-if="item.lastMessagePreview" class="history-item-preview">{{ item.lastMessagePreview }}</span>
          </button>
          <button
            type="button"
            class="history-delete"
            title="删除会话"
            :disabled="busy"
            @click.stop="handleDelete(item.id)"
          >
            ×
          </button>
        </li>
      </ul>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useAuthStore } from '../stores/authStore'
import { useLearningSessionStore } from '../stores/learningSession'
import { toastError, toastSuccess } from '../utils/toast'

const emit = defineEmits(['session-changed'])

const authStore = useAuthStore()
const learningSession = useLearningSessionStore()
const expanded = ref(false)
const busy = ref(false)

const summaries = computed(() => learningSession.sessionSummaries)
const currentTopic = computed(() => learningSession.currentSession?.topic || '')

watch(
  () => authStore.isRegistered,
  (registered) => {
    if (registered) {
      void learningSession.refreshSessionSummaries()
    }
  },
  { immediate: true },
)

function formatDate(value) {
  if (!value) return '刚刚'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '刚刚'
  return date.toLocaleString('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

async function handleNewSession() {
  if (busy.value) return
  busy.value = true
  try {
    await learningSession.startNewSession('新的学习会话')
    emit('session-changed', learningSession.currentSessionId)
    toastSuccess('已创建新会话')
  } catch (error) {
    toastError(error?.message || '创建会话失败')
  } finally {
    busy.value = false
  }
}

async function handleSelect(sessionId) {
  if (busy.value || sessionId === learningSession.currentSessionId) return
  busy.value = true
  try {
    await learningSession.switchToSession(sessionId)
    emit('session-changed', sessionId)
    expanded.value = false
  } catch (error) {
    toastError(error?.message || '切换会话失败')
  } finally {
    busy.value = false
  }
}

async function handleDelete(sessionId) {
  if (busy.value) return
  if (!window.confirm('确定删除该会话及其聊天记录？关联资源也会随会话移除。')) return
  busy.value = true
  try {
    await learningSession.removeSession(sessionId)
    emit('session-changed', learningSession.currentSessionId)
    toastSuccess('会话已删除')
  } catch (error) {
    toastError(error?.message || '删除失败')
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.chat-history-panel {
  border-bottom: 1px solid rgba(15, 118, 110, 0.12);
}

.history-toggle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.65rem 1rem;
  background: rgba(240, 253, 250, 0.6);
  border: none;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
}

.history-toggle-label {
  font-weight: 600;
  color: #0f766e;
  flex-shrink: 0;
}

.history-current {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #334155;
  font-size: 0.9rem;
}

.history-count {
  font-size: 0.78rem;
  color: #64748b;
  flex-shrink: 0;
}

.history-chevron {
  color: #64748b;
  flex-shrink: 0;
}

.history-body {
  padding: 0 1rem 0.75rem;
  background: rgba(248, 250, 252, 0.85);
}

.history-actions {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 0.5rem;
}

.history-hint {
  font-size: 0.78rem;
  color: #64748b;
}

.history-empty {
  font-size: 0.85rem;
  color: #64748b;
  padding: 0.5rem 0;
}

.history-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 220px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.history-item {
  display: flex;
  align-items: stretch;
  gap: 0.25rem;
  border-radius: 8px;
  border: 1px solid transparent;
}

.history-item.active {
  border-color: rgba(15, 118, 110, 0.35);
  background: rgba(204, 251, 241, 0.45);
}

.history-select {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.15rem;
  padding: 0.55rem 0.65rem;
  border: none;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  border-radius: 8px;
}

.history-select:hover {
  background: rgba(255, 255, 255, 0.7);
}

.history-item-topic {
  font-weight: 600;
  font-size: 0.88rem;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.history-item-meta {
  font-size: 0.75rem;
  color: #64748b;
}

.history-item-preview {
  font-size: 0.78rem;
  color: #475569;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.history-delete {
  align-self: center;
  width: 1.75rem;
  height: 1.75rem;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: #94a3b8;
  cursor: pointer;
  font-size: 1.1rem;
  line-height: 1;
  flex-shrink: 0;
}

.history-delete:hover:not(:disabled) {
  background: rgba(239, 68, 68, 0.12);
  color: #dc2626;
}

.history-delete:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
