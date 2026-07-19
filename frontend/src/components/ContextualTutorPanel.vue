<template>
  <aside class="contextual-tutor vt-card" aria-label="AI 老师">
    <header>
      <div>
        <span class="vt-eyebrow">AI 老师 · 多轮辅导</span>
        <h3>{{ title }}</h3>
      </div>
      <button
        type="button"
        class="close-btn"
        aria-label="关闭 AI 老师"
        @click="$emit('close')"
      >
        ×
      </button>
    </header>

    <details class="context-details">
      <summary>查看 AI 老师当前获得的学习上下文</summary>
      <p>{{ contextPreview }}</p>
    </details>

    <label class="mode-picker">
      <span>本次辅导方式</span>
      <select v-model="selectedTutorMode" :disabled="loading">
        <option v-for="mode in availableModes" :key="mode.value" :value="mode.value">
          {{ mode.label }}
        </option>
      </select>
    </label>

    <div class="quick-actions" aria-label="快捷提问">
      <button
        v-for="action in resolvedQuickActions"
        :key="action"
        type="button"
        :disabled="loading"
        @click="askQuickAction(action)"
      >
        {{ action }}
      </button>
    </div>

    <div class="conversation" aria-live="polite">
      <p v-if="historyLoading" class="conversation-empty">
        正在恢复这项学习内容下的历史对话…
      </p>
      <p v-else-if="!messages.length" class="conversation-empty">
        这是当前内容下的第一轮提问。切换题目或教材后，回来仍会保留这里的记录。
      </p>
      <article
        v-for="message in messages"
        :key="message.localId || message.id"
        class="message-bubble"
        :class="message.role"
      >
        <span>{{ message.role === "user" ? "我" : "AI 老师" }}</span>
        <MarkdownPanel
          v-if="message.role === 'assistant'"
          :content="message.content"
        />
        <p v-else>{{ message.content }}</p>
        <div v-if="message.usedRag || message.usedVision" class="answer-badges">
          <span v-if="message.usedRag">已检索知识库</span>
          <span v-if="message.usedVision">已分析图片</span>
        </div>
        <details v-if="message.citations?.length" class="message-sources">
          <summary>查看本轮依据（{{ message.citations.length }}）</summary>
          <ul>
            <li
              v-for="citation in message.citations"
              :key="citation.citationId"
            >
              <strong>{{
                citation.source || citation.sourcePath || "知识库资料"
              }}</strong>
              <span>{{ citation.excerpt }}</span>
            </li>
          </ul>
        </details>
      </article>
      <div ref="conversationEnd"></div>
    </div>

    <form @submit.prevent="submitQuestion">
      <textarea
        v-model="question"
        rows="3"
        placeholder="继续追问：这一步为什么错？能换一种更简单的讲法吗？"
        :disabled="loading"
      ></textarea>

      <div v-if="imagePreview" class="image-preview">
        <img :src="imagePreview" alt="准备发送给 AI 老师的图片" />
        <button type="button" @click="clearImage">移除图片</button>
      </div>

      <div class="tutor-actions">
        <div class="input-actions">
          <label class="attach-btn">
            <input
              type="file"
              accept="image/*"
              :disabled="loading"
              @change="selectImage"
            />
            上传图片
          </label>
          <button
            type="button"
            class="voice-btn"
            :disabled="!speechSupported || loading"
            :title="speechSupported ? '语音输入' : '当前浏览器不支持语音输入'"
            @click="toggleSpeech"
          >
            {{ listening ? "停止语音" : "语音输入" }}
          </button>
        </div>
        <button
          type="submit"
          class="vt-btn vt-btn-primary vt-btn-sm"
          :disabled="loading || !question.trim()"
        >
          {{ loading ? "正在分析…" : "发送" }}
        </button>
      </div>
    </form>

    <p v-if="statusMessage" class="tutor-status" role="status">
      {{ statusMessage }}
    </p>
  </aside>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import MarkdownPanel from "./common/MarkdownPanel.vue";
import { askContextualTutor } from "../api/tutoring";
import { listSessionChatMessages } from "../api/learning";
import { useUserProfileStore } from "../stores/userProfile";
import { serializeProfileSnapshot } from "../utils/profileSnapshot";

const props = defineProps({
  title: { type: String, default: "针对当前内容提问" },
  context: { type: String, default: "" },
  suggestedQuestion: { type: String, default: "" },
  learningSessionId: { type: [Number, String], default: null },
  contextType: { type: String, default: "CONTEXTUAL_TUTOR" },
  contextKey: { type: String, default: "" },
  contextTitle: { type: String, default: "" },
  answerMode: { type: String, default: "FREE" },
  quickActions: {
    type: Array,
    default: () => [
      "只给提示",
      "检查我的思路",
      "为什么我的答案不对",
      "分步骤解释",
      "换一种简单讲法",
      "举一个类似例子",
      "根据我的错误生成专项练习",
    ],
  },
});

const emit = defineEmits(["close", "message"]);
const userProfile = useUserProfileStore();
const question = ref(props.suggestedQuestion);
const image = ref(null);
const imagePreview = ref("");
const messages = ref([]);
const loading = ref(false);
const historyLoading = ref(false);
const listening = ref(false);
const statusMessage = ref("");
const selectedTutorMode = ref(props.answerMode === "HINT_ONLY" ? "HINT" : "AUTO");
const conversationEnd = ref(null);
const SpeechRecognition =
  globalThis.SpeechRecognition || globalThis.webkitSpeechRecognition;
const speechSupported = Boolean(SpeechRecognition);
let recognition = null;
let historyRequestId = 0;

const resolvedQuickActions = computed(() =>
  props.quickActions.filter((item) => String(item || "").trim()).slice(0, 7),
);
const availableModes = computed(() => {
  const modes = [
    { value: "AUTO", label: "自动选择" },
    { value: "HINT", label: "只给提示" },
    { value: "STEP_BY_STEP", label: "分步带我做" },
    { value: "DIRECT_ANSWER", label: "直接看答案" },
  ];
  return props.answerMode === "HINT_ONLY"
    ? modes.filter((mode) => mode.value === "HINT" || mode.value === "STEP_BY_STEP")
    : modes;
});
const contextPreview = computed(() => {
  const text = String(props.context || "")
    .replace(/\s+/g, " ")
    .trim();
  return text.length > 420 ? `${text.slice(0, 420)}…` : text || "当前页面内容";
});

async function loadHistory() {
  const requestId = ++historyRequestId;
  messages.value = [];
  if (!props.learningSessionId || !props.contextKey) return;
  historyLoading.value = true;
  try {
    const rows = await listSessionChatMessages(props.learningSessionId, {
      contextType: props.contextType,
      contextKey: props.contextKey,
      silent: true,
    });
    if (requestId !== historyRequestId) return;
    messages.value = Array.isArray(rows)
      ? rows.map((row) => ({
          ...row,
          ...parseMessageMetadata(row.metadataJson),
        }))
      : [];
    await scrollConversation();
  } catch (error) {
    if (requestId === historyRequestId) {
      statusMessage.value =
        error?.message || "历史对话暂时无法恢复，本轮仍可继续提问。";
    }
  } finally {
    if (requestId === historyRequestId) historyLoading.value = false;
  }
}

function selectImage(event) {
  const file = event.target.files?.[0];
  if (!file) return;
  if (!file.type.startsWith("image/") || file.size > 10 * 1024 * 1024) {
    statusMessage.value = "请选择 10MB 以内的图片文件。";
    event.target.value = "";
    return;
  }
  clearImage();
  image.value = file;
  imagePreview.value = URL.createObjectURL(file);
  statusMessage.value = "";
}

function clearImage() {
  if (imagePreview.value) URL.revokeObjectURL(imagePreview.value);
  image.value = null;
  imagePreview.value = "";
}

function toggleSpeech() {
  if (!speechSupported) return;
  if (listening.value && recognition) {
    recognition.stop();
    return;
  }
  recognition = new SpeechRecognition();
  recognition.lang = "zh-CN";
  recognition.interimResults = true;
  recognition.continuous = false;
  const before = question.value.trim();
  recognition.onstart = () => {
    listening.value = true;
    statusMessage.value = "正在听，请说出你的问题…";
  };
  recognition.onresult = (event) => {
    const transcript = Array.from(event.results)
      .map((result) => result[0]?.transcript || "")
      .join("");
    question.value = [before, transcript]
      .filter(Boolean)
      .join(before ? " " : "");
  };
  recognition.onerror = () => {
    statusMessage.value = "语音识别未完成，可以改用文字输入。";
  };
  recognition.onend = () => {
    listening.value = false;
    if (statusMessage.value.startsWith("正在听"))
      statusMessage.value = "语音已转换为文字，可继续编辑后发送。";
  };
  recognition.start();
}

async function askQuickAction(action) {
  if (String(action).includes("提示")) selectedTutorMode.value = "HINT";
  if (String(action).includes("分步")) selectedTutorMode.value = "STEP_BY_STEP";
  question.value = action;
  await submitQuestion();
}

async function submitQuestion() {
  const prompt = question.value.trim();
  if (!prompt || loading.value) return;
  loading.value = true;
  statusMessage.value = "正在结合当前内容、历史对话和学习画像分析…";
  const userMessage = {
    localId: `user-${Date.now()}`,
    role: "user",
    content: prompt,
  };
  messages.value.push(userMessage);
  question.value = "";
  await scrollConversation();
  try {
    const result = await askContextualTutor({
      question: prompt,
      context: props.context,
      learnerProfile: serializeProfileSnapshot(
        userProfile.profileSnapshot,
        userProfile.profileDimensions,
        userProfile.aiTeacherPreferences,
      ),
      image: image.value,
      learningSessionId: props.learningSessionId,
      contextType: props.contextType,
      contextKey: props.contextKey,
      contextTitle: props.contextTitle || props.title,
      answerMode: resolvedAnswerMode(),
    });
    const assistantMessage = {
      localId: `assistant-${Date.now()}`,
      role: "assistant",
      content: result.answer || "本次没有生成有效回答，请重试。",
      usedRag: Boolean(result.usedRag),
      usedVision: Boolean(result.usedVision),
      citations: Array.isArray(result.citations) ? result.citations : [],
    };
    messages.value.push(assistantMessage);
    emit("message", assistantMessage);
    clearImage();
    statusMessage.value = "";
    await scrollConversation();
  } catch (error) {
    question.value = prompt;
    statusMessage.value =
      error?.response?.data?.message ||
      error?.message ||
      "AI 老师暂时无法回答，请稍后重试。";
  } finally {
    loading.value = false;
  }
}

function resolvedAnswerMode() {
  if (props.answerMode === "HINT_ONLY" && selectedTutorMode.value === "DIRECT_ANSWER") {
    return "HINT_ONLY";
  }
  if (selectedTutorMode.value === "HINT") return "HINT_ONLY";
  if (selectedTutorMode.value === "STEP_BY_STEP") return "STEP_BY_STEP";
  if (selectedTutorMode.value === "DIRECT_ANSWER") return "DIRECT_ANSWER";
  return props.answerMode || "FREE";
}

async function scrollConversation() {
  await nextTick();
  conversationEnd.value?.scrollIntoView({ block: "nearest" });
}

function parseMessageMetadata(value) {
  if (!value) return {};
  try {
    const parsed = typeof value === "string" ? JSON.parse(value) : value;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

watch(
  () => [props.learningSessionId, props.contextType, props.contextKey],
  loadHistory,
  { immediate: true },
);
watch(
  () => props.suggestedQuestion,
  (value) => {
    if (!question.value.trim()) question.value = value || "";
  },
);

onBeforeUnmount(() => {
  if (recognition && listening.value) recognition.stop();
  clearImage();
});
</script>

<style scoped>
.contextual-tutor {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-4);
  border-color: rgba(13, 148, 136, 0.3);
}

.contextual-tutor header,
.tutor-actions,
.input-actions,
.answer-badges {
  display: flex;
  align-items: center;
}

.contextual-tutor header,
.tutor-actions {
  justify-content: space-between;
  gap: var(--vt-space-3);
}

.contextual-tutor h3 {
  margin: var(--vt-space-1) 0 0;
  color: var(--vt-text-primary);
  font-size: var(--vt-text-base);
}

.close-btn,
.voice-btn,
.attach-btn {
  border: 0;
  background: transparent;
  color: var(--vt-text-secondary);
  cursor: pointer;
  font: inherit;
  font-size: var(--vt-text-xs);
}

.close-btn {
  font-size: 1.4rem;
}

.context-details {
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-xs);
}

.context-details summary {
  padding: var(--vt-space-2) var(--vt-space-3);
  cursor: pointer;
  font-weight: var(--vt-font-semibold);
}

.context-details p {
  margin: 0;
  padding: 0 var(--vt-space-3) var(--vt-space-3);
  line-height: 1.55;
}

.quick-actions {
  display: flex;
  gap: var(--vt-space-2);
  padding-bottom: 2px;
  overflow-x: auto;
}

.mode-picker {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vt-space-3);
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-xs);
}

.mode-picker select {
  min-width: 132px;
  padding: 6px 9px;
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-primary);
  color: var(--vt-text-primary);
}

.quick-actions button {
  flex: 0 0 auto;
  padding: 5px 9px;
  border: 1px solid rgba(13, 148, 136, 0.22);
  border-radius: 999px;
  background: rgba(13, 148, 136, 0.05);
  color: var(--vt-accent-teal-dark);
  cursor: pointer;
  font: inherit;
  font-size: 11px;
}

.conversation {
  display: grid;
  align-content: start;
  gap: var(--vt-space-3);
  max-height: 420px;
  min-height: 120px;
  padding: var(--vt-space-2);
  overflow-y: auto;
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
}

.conversation-empty {
  margin: auto;
  padding: var(--vt-space-3);
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
  line-height: 1.6;
  text-align: center;
}

.message-bubble {
  display: grid;
  gap: var(--vt-space-1);
  max-width: 92%;
  padding: var(--vt-space-2) var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-primary);
}

.message-bubble.user {
  justify-self: end;
  background: rgba(13, 148, 136, 0.12);
}

.message-bubble > span {
  color: var(--vt-text-tertiary);
  font-size: 10px;
  font-weight: var(--vt-font-semibold);
}

.message-bubble p {
  margin: 0;
  overflow-wrap: anywhere;
  line-height: 1.55;
}

.contextual-tutor form {
  display: grid;
  gap: var(--vt-space-3);
}

.contextual-tutor textarea {
  width: 100%;
  padding: var(--vt-space-3);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-primary);
  color: var(--vt-text-primary);
  resize: vertical;
  font: inherit;
  line-height: 1.6;
}

.input-actions,
.answer-badges {
  gap: var(--vt-space-2);
}

.attach-btn input {
  display: none;
}

.voice-btn:disabled,
.attach-btn:has(input:disabled) {
  opacity: 0.45;
  cursor: not-allowed;
}

.image-preview {
  display: flex;
  align-items: center;
  gap: var(--vt-space-3);
}

.image-preview img {
  width: 72px;
  height: 72px;
  border-radius: var(--vt-radius-md);
  object-fit: cover;
}

.image-preview button {
  border: 0;
  background: none;
  color: #b91c1c;
  cursor: pointer;
}

.answer-badges span {
  padding: 3px 7px;
  border-radius: 999px;
  background: rgba(13, 148, 136, 0.1);
  color: var(--vt-accent-teal-dark);
  font-size: 10px;
}

.message-sources {
  color: var(--vt-text-secondary);
  font-size: 10px;
}

.message-sources summary {
  cursor: pointer;
  font-weight: var(--vt-font-semibold);
}

.message-sources ul {
  display: grid;
  gap: var(--vt-space-2);
  margin: var(--vt-space-2) 0 0;
  padding-left: 1rem;
}

.message-sources li,
.message-sources li span {
  display: grid;
  gap: 2px;
}

.tutor-status {
  margin: 0;
  color: var(--vt-text-secondary);
  font-size: var(--vt-text-xs);
}

@media (max-width: 560px) {
  .tutor-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .conversation {
    max-height: 360px;
  }
}
</style>
