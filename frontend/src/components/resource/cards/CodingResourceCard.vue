<template>
  <section v-if="content" class="code-lab">
    <header class="lab-header">
      <div>
        <span class="vt-eyebrow">正式代码实验室 · Python</span>
        <h3>{{ goal }}</h3>
        <p>
          优先在浏览器 Web Worker + Pyodide
          中隔离运行；不支持时才回退到无网络、限时限额的服务端 Docker 沙箱。
        </p>
      </div>
      <span class="version-badge">版本 v{{ versions.length }}</span>
    </header>

    <div class="lab-controls">
      <button
        v-if="available"
        type="button"
        class="vt-btn vt-btn-primary vt-btn-sm"
        :disabled="loading"
        @click="run"
      >
        {{ loading ? "执行中…" : "运行" }}
      </button>
      <button
        type="button"
        class="vt-btn vt-btn-outline vt-btn-sm"
        :disabled="!loading"
        @click="emit('stop')"
      >
        停止
      </button>
      <button
        type="button"
        class="vt-btn vt-btn-ghost vt-btn-sm"
        :disabled="loading"
        @click="reset"
      >
        重置
      </button>
      <button
        type="button"
        class="vt-btn vt-btn-ghost vt-btn-sm"
        :disabled="loading"
        @click="saveVersion"
      >
        保存版本
      </button>
      <select
        v-model="selectedVersion"
        aria-label="代码版本"
        @change="restoreVersion"
      >
        <option value="">版本记录</option>
        <option
          v-for="version in versions"
          :key="version.id"
          :value="version.id"
        >
          v{{ version.order }} · {{ version.label }}
        </option>
      </select>
    </div>

    <textarea
      ref="editor"
      v-model="editableCode"
      class="code-editor"
      aria-label="Python 代码编辑器"
      spellcheck="false"
      @keydown.tab.prevent="insertIndent"
    />

    <div class="assertion-grid">
      <label>
        <span>预期输出（可选，用于自动测试）</span>
        <textarea
          v-model="expectedOutput"
          rows="3"
          placeholder="例如：torch.Size([1, 16, 14, 14])"
        ></textarea>
      </label>
      <div>
        <span>测试结论</span>
        <strong :class="testStatusClass">{{ testStatus }}</strong>
        <p>比较规则：去除首尾空白后精确比较；空预期只检查程序是否成功结束。</p>
      </div>
    </div>

    <div v-if="loading" class="run-state">
      沙箱执行中；可随时“停止”，浏览器 Worker 会被立即终止。
    </div>
    <div v-else-if="!available" class="run-state unavailable">
      {{ availabilityMessage || "代码沙箱暂不可用。" }}
    </div>
    <div v-else-if="failed || unavailable" class="result-panel error-panel">
      <header>
        <strong>{{ unavailable ? "沙箱暂不可用" : statusLabel }}</strong
        ><span v-if="executionTimeMs">{{ executionTimeMs }} ms</span>
      </header>
      <pre>{{ errorLog }}</pre>
    </div>
    <div v-else-if="passed || outputLog" class="result-panel success-panel">
      <header>
        <strong>实际输出</strong
        ><span v-if="executionTimeMs">{{ executionTimeMs }} ms</span>
      </header>
      <pre>{{ outputLog || "程序成功结束（无标准输出）" }}</pre>
    </div>

    <details class="lab-tests">
      <summary>安全边界与测试说明</summary>
      <ul>
        <li>浏览器任务运行在独立 Worker，超时或停止时直接终止 Worker。</li>
        <li>
          服务端回退容器禁用网络，限制 CPU、内存和运行时间，并在结束后强制清理。
        </li>
        <li>
          输出会截断，避免无限打印拖垮页面；请勿在实验中放入隐私信息或密钥。
        </li>
      </ul>
    </details>

    <ContextualTutorPanel
      v-if="showTutor"
      title="代码实验 AI 老师"
      :context="tutorContext"
      :learning-session-id="learningSessionId"
      context-type="CODE_LAB"
      :context-key="contextKey"
      context-title="代码实验室"
      answer-mode="FREE"
      @close="showTutor = false"
    />
    <button
      v-else
      type="button"
      class="vt-btn vt-btn-outline vt-btn-sm tutor-button"
      @click="showTutor = true"
    >
      让 AI 老师检查错误、实际输出和下一步
    </button>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import ContextualTutorPanel from "../../ContextualTutorPanel.vue";

const props = withDefaults(
  defineProps<{
    content?: string;
    loading?: boolean;
    passed?: boolean;
    failed?: boolean;
    unavailable?: boolean;
    statusLabel?: string;
    errorLog?: string;
    outputLog?: string;
    executionTimeMs?: number | null;
    available?: boolean;
    availabilityMessage?: string;
    learningSessionId?: number | null;
    contextKey?: string;
  }>(),
  {
    content: "",
    loading: false,
    passed: false,
    failed: false,
    unavailable: false,
    statusLabel: "执行失败",
    errorLog: "",
    outputLog: "",
    executionTimeMs: null,
    available: true,
    availabilityMessage: "",
    learningSessionId: null,
    contextKey: "code-lab",
  },
);
const emit = defineEmits<{ execute: [code: string]; stop: [] }>();
const editor = ref<HTMLTextAreaElement | null>(null);
const editableCode = ref("");
const initialCode = ref("");
const expectedOutput = ref("");
const versions = ref<
  Array<{ id: string; order: number; label: string; code: string }>
>([]);
const selectedVersion = ref("");
const showTutor = ref(false);
const goal = computed(() => {
  const heading = props.content.match(/^\s*#\s+(.+)$/m)?.[1];
  return heading || "修改并运行代码，用输出和测试证明你的实现";
});
const testStatus = computed(() => {
  if (props.loading) return "等待执行完成";
  if (props.failed || props.unavailable) return "未通过：程序未成功结束";
  if (!props.passed && !props.outputLog) return "尚未运行";
  if (!expectedOutput.value.trim()) return "测试通过：程序成功结束";
  return props.outputLog.trim() === expectedOutput.value.trim()
    ? "测试通过：输出符合预期"
    : "未通过：预期与实际不一致";
});
const testStatusClass = computed(() =>
  testStatus.value.startsWith("测试通过")
    ? "test-pass"
    : testStatus.value.startsWith("未通过")
      ? "test-fail"
      : "",
);
const tutorContext = computed(() =>
  [
    `实验目标：${goal.value}`,
    `当前代码：\n${editableCode.value}`,
    `预期输出：${expectedOutput.value || "未填写"}`,
    `实际输出：${props.outputLog || "无"}`,
    `错误日志：${props.errorLog || "无"}`,
    `测试结论：${testStatus.value}`,
  ].join("\n\n"),
);

watch(
  () => props.content,
  (value) => {
    editableCode.value = value || "";
    initialCode.value = value || "";
    versions.value = [
      { id: "initial", order: 1, label: "初始代码", code: value || "" },
    ];
  },
  { immediate: true },
);

function run() {
  saveVersion("运行前");
  emit("execute", editableCode.value);
}
function reset() {
  editableCode.value = initialCode.value;
  selectedVersion.value = "initial";
}
function saveVersion(label = "手动保存") {
  const last = versions.value.at(-1);
  if (last?.code === editableCode.value) return;
  const order = versions.value.length + 1;
  versions.value.push({
    id: `${Date.now()}-${order}`,
    order,
    label,
    code: editableCode.value,
  });
}
function restoreVersion() {
  const version = versions.value.find(
    (entry) => entry.id === selectedVersion.value,
  );
  if (version) editableCode.value = version.code;
}
async function insertIndent() {
  const target = editor.value;
  if (!target) return;
  const start = target.selectionStart;
  const end = target.selectionEnd;
  editableCode.value = `${editableCode.value.slice(0, start)}    ${editableCode.value.slice(end)}`;
  await nextTick();
  target.selectionStart = target.selectionEnd = start + 4;
}
</script>

<style scoped>
.code-lab {
  display: grid;
  gap: 0.8rem;
  padding: 0.85rem;
  border: 1px solid rgba(15, 118, 110, 0.2);
  border-radius: 12px;
  background: rgba(15, 118, 110, 0.025);
}
.lab-header,
.lab-controls,
.result-panel header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 0.75rem;
}
.lab-header h3 {
  margin: 0.25rem 0;
}
.lab-header p {
  margin: 0;
  color: var(--vt-text-secondary);
  font-size: 0.78rem;
  line-height: 1.5;
}
.version-badge {
  white-space: nowrap;
  padding: 0.2rem 0.5rem;
  border-radius: 999px;
  background: rgba(79, 70, 229, 0.1);
  font-size: 0.75rem;
}
.lab-controls {
  justify-content: flex-start;
  flex-wrap: wrap;
}
.lab-controls select {
  padding: 0.4rem;
  border-radius: 7px;
  border: 1px solid rgba(148, 163, 184, 0.4);
}
.code-editor {
  width: 100%;
  min-height: 280px;
  resize: vertical;
  border: 1px solid #334155;
  border-radius: 10px;
  padding: 14px;
  background: #0f172a;
  color: #e2e8f0;
  font:
    12px/1.65 ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  tab-size: 4;
}
.assertion-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.8rem;
}
.assertion-grid label,
.assertion-grid > div {
  display: grid;
  gap: 0.35rem;
}
.assertion-grid textarea {
  padding: 0.55rem;
  border: 1px solid rgba(148, 163, 184, 0.4);
  border-radius: 8px;
  font:
    12px/1.5 ui-monospace,
    monospace;
}
.assertion-grid p {
  margin: 0;
  color: var(--vt-text-secondary);
  font-size: 0.72rem;
}
.test-pass {
  color: #047857;
}
.test-fail {
  color: #b91c1c;
}
.run-state,
.result-panel {
  padding: 0.7rem;
  border-radius: 8px;
  font-size: 0.82rem;
}
.run-state {
  background: rgba(79, 70, 229, 0.06);
}
.unavailable {
  background: #fffbeb;
}
.result-panel pre {
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  margin: 0.55rem 0 0;
  font:
    12px/1.5 ui-monospace,
    monospace;
}
.success-panel {
  background: #ecfdf5;
  color: #065f46;
}
.error-panel {
  background: #fff1f2;
  color: #9f1239;
}
.lab-tests {
  font-size: 0.78rem;
  color: var(--vt-text-secondary);
}
.lab-tests li {
  margin: 0.35rem 0;
}
.tutor-button {
  justify-self: start;
}
@media (max-width: 680px) {
  .lab-header,
  .assertion-grid {
    grid-template-columns: 1fr;
  }
  .lab-header {
    flex-direction: column;
  }
}
</style>
