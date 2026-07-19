<template>
  <main class="sandbox-view vt-container vt-section">
    <header class="sandbox-header">
      <div>
        <span class="vt-eyebrow">独立代码沙箱</span>
        <h1 class="vt-title">写代码、跑测试，再让 AI 解释结果</h1>
        <p class="vt-text-muted">
          Python 在浏览器隔离 Worker 中运行，不依赖云服务器 Python 或
          Docker。单次最多运行 30 秒，输出最多保留 2 万字符。
        </p>
      </div>
      <RouterLink class="vt-btn vt-btn-ghost" to="/labs"
        >返回互动实验</RouterLink
      >
    </header>

    <section class="sandbox-layout">
      <aside class="history-panel vt-card">
        <header>
          <div>
            <span class="vt-eyebrow">运行历史</span>
            <strong>{{ history.length }} 条</strong>
          </div>
          <button
            v-if="history.length"
            type="button"
            class="vt-btn vt-btn-ghost vt-btn-sm"
            @click="clearHistory"
          >
            清空
          </button>
        </header>
        <input
          v-model.trim="historyQuery"
          class="vt-input"
          type="search"
          placeholder="搜索代码或标题"
        />
        <div v-if="filteredHistory.length" class="history-list">
          <article
            v-for="item in filteredHistory"
            :key="item.id"
            class="history-item"
          >
            <button
              type="button"
              class="history-open"
              @click="restoreHistory(item)"
            >
              <strong>{{ item.title }}</strong>
              <small
                >{{ formatTime(item.createdAt) }} ·
                {{ statusText(item.status) }}</small
              >
            </button>
            <button
              type="button"
              class="history-delete"
              title="删除记录"
              @click="deleteHistory(item.id)"
            >
              ×
            </button>
          </article>
        </div>
        <p v-else class="empty-copy">运行代码后会保存最近 30 条记录。</p>
      </aside>

      <section class="workspace-panel">
        <article class="editor-card vt-card">
          <header class="panel-heading">
            <div>
              <span class="vt-eyebrow">Python 编辑器</span>
              <input
                v-model.trim="title"
                class="title-input"
                maxlength="80"
                aria-label="实验标题"
              />
            </div>
            <div class="editor-actions">
              <button
                type="button"
                class="vt-btn vt-btn-ghost vt-btn-sm"
                :disabled="running"
                @click="resetExample"
              >
                恢复示例
              </button>
              <button
                v-if="running"
                type="button"
                class="vt-btn vt-btn-outline vt-btn-sm"
                @click="stopRun"
              >
                停止运行
              </button>
              <button
                v-else
                type="button"
                class="vt-btn vt-btn-primary vt-btn-sm"
                @click="runCode"
              >
                运行并测试
              </button>
            </div>
          </header>
          <textarea
            v-model="code"
            class="code-editor"
            spellcheck="false"
            aria-label="Python 代码"
            @keydown.tab.prevent="insertIndent"
          ></textarea>
        </article>

        <article class="test-card vt-card">
          <header class="panel-heading">
            <div>
              <span class="vt-eyebrow">测试用例</span>
              <h2>用表达式验证代码结果</h2>
            </div>
            <button
              type="button"
              class="vt-btn vt-btn-ghost vt-btn-sm"
              @click="addTestCase"
            >
              添加用例
            </button>
          </header>
          <div class="test-table">
            <div class="test-row test-labels" aria-hidden="true">
              <span>表达式</span><span>期望值</span><span></span>
            </div>
            <div
              v-for="(testCase, index) in testCases"
              :key="testCase.id"
              class="test-row"
            >
              <input
                v-model="testCase.expression"
                class="vt-input code-input"
                :aria-label="`测试 ${index + 1} 表达式`"
              />
              <input
                v-model="testCase.expected"
                class="vt-input code-input"
                :aria-label="`测试 ${index + 1} 期望值`"
              />
              <button
                type="button"
                title="删除用例"
                @click="removeTestCase(testCase.id)"
              >
                ×
              </button>
            </div>
          </div>
          <p class="test-help">
            示例：表达式填写 <code>output_size(7, 3, 1, 2)</code>，期望值填写
            <code>4</code>。
          </p>
        </article>

        <article class="result-card vt-card" aria-live="polite">
          <header class="panel-heading">
            <div>
              <span class="vt-eyebrow">运行结果</span>
              <h2>{{ resultHeadline }}</h2>
            </div>
            <span
              v-if="report"
              :class="['result-badge', report.status.toLowerCase()]"
            >
              {{ statusText(report.status) }}
            </span>
          </header>
          <p v-if="running" class="running-copy">
            首次运行正在加载约 10–20MB 的本地 Python 运行时，请稍候…
          </p>
          <template v-else-if="report">
            <div v-if="testResults.length" class="test-results">
              <div
                v-for="(result, index) in testResults"
                :key="`${result.expression}-${index}`"
                :class="result.passed ? 'passed' : 'failed'"
              >
                <strong
                  >{{ result.passed ? "通过" : "失败" }} ·
                  {{ result.expression }}</strong
                >
                <span
                  >期望 {{ result.expected }}，实际 {{ result.actual }}</span
                >
              </div>
            </div>
            <pre v-if="report.output">{{ cleanOutput }}</pre>
            <pre v-if="report.error" class="error-output">{{
              report.error
            }}</pre>
            <small>执行时间：{{ report.execution_time_ms || 0 }}ms</small>
          </template>
          <p v-else class="empty-copy">
            点击“运行并测试”后，这里会显示输出、错误与逐例结果。
          </p>
        </article>
      </section>

      <aside class="tutor-panel">
        <ContextualTutorPanel
          title="代码实验 AI 老师"
          :context="tutorContext"
          suggested-question="请根据运行结果定位问题，先解释原因，再给最小修改和验证方法。"
          :learning-session-id="learningSession.currentSessionId"
          context-type="CODE_SANDBOX"
          :context-key="`code-sandbox:${activeHistoryId || 'draft'}`"
          :context-title="title || '代码沙箱实验'"
        />
      </aside>
    </section>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { RouterLink } from "vue-router";
import ContextualTutorPanel from "../components/ContextualTutorPanel.vue";
import { useAuthStore } from "../stores/authStore";
import { useLearningSessionStore } from "../stores/learningSession";
import {
  runPythonInBrowser,
  stopPythonInBrowser,
} from "../utils/pyodideSandbox";

const EXAMPLE_CODE = `def output_size(size, kernel, padding=0, stride=1):
    return (size + 2 * padding - kernel) // stride + 1

result = output_size(7, 3, 1, 2)
print("卷积输出尺寸:", result)`;
const TEST_MARKER = "__VISIONARY_TEST_RESULTS__=";
let runSequence = 0;

function createId() {
  return (
    globalThis.crypto?.randomUUID?.() ||
    `${Date.now()}-${Math.random().toString(16).slice(2)}`
  );
}

const authStore = useAuthStore();
const learningSession = useLearningSessionStore();
const title = ref("卷积输出尺寸验证");
const code = ref(EXAMPLE_CODE);
const running = ref(false);
const report = ref(null);
const testResults = ref([]);
const history = ref([]);
const historyQuery = ref("");
const activeHistoryId = ref("");
const testCases = reactive([
  { id: createId(), expression: "output_size(7, 3, 1, 2)", expected: "4" },
  { id: createId(), expression: "output_size(5, 3, 0, 1)", expected: "3" },
]);

const historyKey = computed(
  () => `vt_code_sandbox_history:${authStore.currentUserId || "guest"}`,
);
const filteredHistory = computed(() => {
  const query = historyQuery.value.toLowerCase();
  return history.value.filter(
    (item) =>
      !query || `${item.title} ${item.code}`.toLowerCase().includes(query),
  );
});
const resultHeadline = computed(() => {
  if (running.value) return "正在运行";
  if (!report.value) return "等待运行";
  if (report.value.status !== "SUCCESS") return "代码需要修正";
  if (testResults.value.some((item) => !item.passed))
    return "代码已运行，但部分测试未通过";
  return testResults.value.length ? "全部测试通过" : "代码运行成功";
});
const cleanOutput = computed(() =>
  String(report.value?.output || "")
    .split("\n")
    .filter((line) => !line.startsWith(TEST_MARKER))
    .join("\n"),
);
const tutorContext = computed(() =>
  [
    `实验：${title.value}`,
    `代码：\n\`\`\`python\n${code.value}\n\`\`\``,
    testCases.length
      ? `测试用例：\n${testCases.map((item) => `${item.expression} => ${item.expected}`).join("\n")}`
      : "未设置测试用例",
    report.value
      ? `运行状态：${report.value.status}\n输出：${cleanOutput.value}\n错误：${report.value.error || "无"}`
      : "尚未运行",
    testResults.value.length
      ? `逐例结果：${JSON.stringify(testResults.value)}`
      : "",
  ]
    .filter(Boolean)
    .join("\n\n"),
);

function addTestCase() {
  testCases.push({ id: createId(), expression: "", expected: "" });
}

function removeTestCase(id) {
  const index = testCases.findIndex((item) => item.id === id);
  if (index >= 0) testCases.splice(index, 1);
}

function buildHarness() {
  const activeCases = testCases.filter(
    (item) => item.expression.trim() && item.expected.trim(),
  );
  if (!activeCases.length) return code.value;
  const encoded = JSON.stringify(
    activeCases.map(({ expression, expected }) => ({ expression, expected })),
  );
  return `${code.value}\n\nimport json as __visionary_json\n__visionary_cases = __visionary_json.loads(${JSON.stringify(encoded)})\n__visionary_results = []\nfor __case in __visionary_cases:\n    try:\n        __actual = repr(eval(__case["expression"], globals()))\n        __passed = __actual.strip() == str(__case["expected"]).strip()\n        __visionary_results.append({"expression": __case["expression"], "expected": __case["expected"], "actual": __actual, "passed": __passed})\n    except Exception as __error:\n        __visionary_results.append({"expression": __case["expression"], "expected": __case["expected"], "actual": type(__error).__name__ + ": " + str(__error), "passed": False})\nprint("${TEST_MARKER}" + __visionary_json.dumps(__visionary_results, ensure_ascii=False))`;
}

async function runCode() {
  if (!code.value.trim() || running.value) return;
  const currentRun = ++runSequence;
  running.value = true;
  report.value = null;
  testResults.value = [];
  const result = await runPythonInBrowser(buildHarness());
  if (currentRun !== runSequence) return;
  report.value = result;
  const markerLine = String(result.output || "")
    .split("\n")
    .find((line) => line.startsWith(TEST_MARKER));
  if (markerLine) {
    try {
      testResults.value = JSON.parse(markerLine.slice(TEST_MARKER.length));
    } catch {
      testResults.value = [];
    }
  }
  saveHistory();
  running.value = false;
}

function stopRun() {
  runSequence += 1;
  stopPythonInBrowser();
  running.value = false;
  report.value = {
    status: "STOPPED",
    output: "",
    error: "运行已由你停止。可以修改代码后重新运行。",
    execution_time_ms: 0,
  };
}

function resetExample() {
  title.value = "卷积输出尺寸验证";
  code.value = EXAMPLE_CODE;
  testCases.splice(
    0,
    testCases.length,
    { id: createId(), expression: "output_size(7, 3, 1, 2)", expected: "4" },
    { id: createId(), expression: "output_size(5, 3, 0, 1)", expected: "3" },
  );
  report.value = null;
  testResults.value = [];
  activeHistoryId.value = "";
}

function saveHistory() {
  const item = {
    id: createId(),
    title: title.value || "未命名代码实验",
    code: code.value,
    testCases: testCases.map(({ expression, expected }) => ({
      expression,
      expected,
    })),
    status: report.value?.status || "UNKNOWN",
    output: cleanOutput.value.slice(0, 4000),
    createdAt: new Date().toISOString(),
  };
  history.value = [item, ...history.value].slice(0, 30);
  activeHistoryId.value = item.id;
  persistHistory();
}

function restoreHistory(item) {
  title.value = item.title;
  code.value = item.code;
  testCases.splice(
    0,
    testCases.length,
    ...(item.testCases || []).map((entry) => ({ ...entry, id: createId() })),
  );
  report.value = item.output
    ? {
        status: item.status,
        output: item.output,
        error: "",
        execution_time_ms: 0,
      }
    : null;
  testResults.value = [];
  activeHistoryId.value = item.id;
}

function deleteHistory(id) {
  history.value = history.value.filter((item) => item.id !== id);
  persistHistory();
}

function clearHistory() {
  if (
    !window.confirm("清空全部代码运行历史？此操作不会删除生成的代码实验资源。")
  )
    return;
  history.value = [];
  persistHistory();
}

function persistHistory() {
  localStorage.setItem(historyKey.value, JSON.stringify(history.value));
}

function formatTime(value) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function statusText(status) {
  return (
    {
      SUCCESS: "成功",
      ERROR: "运行错误",
      BLOCKED: "已拦截",
      TIMEOUT: "已超时",
      UNAVAILABLE: "不可用",
    }[status] || "未知"
  );
}

function insertIndent(event) {
  const target = event.target;
  const start = target.selectionStart;
  code.value = `${code.value.slice(0, start)}    ${code.value.slice(target.selectionEnd)}`;
  requestAnimationFrame(() => {
    target.selectionStart = target.selectionEnd = start + 4;
  });
}

onMounted(async () => {
  try {
    history.value = JSON.parse(localStorage.getItem(historyKey.value) || "[]");
  } catch {
    history.value = [];
  }
  await learningSession.ensureCurrentSession("代码沙箱学习").catch(() => null);
});

onBeforeUnmount(() => {
  if (running.value) stopPythonInBrowser();
});
</script>

<style scoped>
.sandbox-view {
  max-width: 1540px;
  display: grid;
  gap: 1rem;
}
.sandbox-header,
.panel-heading,
.history-panel header,
.editor-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
}
.sandbox-header p {
  max-width: 900px;
  line-height: 1.75;
}
.sandbox-layout {
  display: grid;
  grid-template-columns: 230px minmax(0, 1fr) 340px;
  gap: 1rem;
  align-items: start;
}
.history-panel,
.editor-card,
.test-card,
.result-card {
  padding: 1rem;
}
.history-panel,
.tutor-panel {
  position: sticky;
  top: 84px;
  max-height: calc(100vh - 100px);
  overflow: auto;
}
.history-panel {
  display: grid;
  gap: 0.8rem;
}
.history-list {
  display: grid;
}
.history-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  border-top: 1px solid var(--vt-border-light);
}
.history-open,
.history-delete {
  border: 0;
  background: transparent;
  cursor: pointer;
}
.history-open {
  display: grid;
  gap: 0.2rem;
  padding: 0.75rem 0;
  text-align: left;
}
.history-open small {
  color: var(--vt-text-tertiary);
}
.history-delete {
  color: var(--vt-text-tertiary);
}
.workspace-panel {
  min-width: 0;
  display: grid;
  gap: 1rem;
}
.editor-card,
.test-card,
.result-card {
  display: grid;
  gap: 0.8rem;
}
.panel-heading h2 {
  margin: 0.25rem 0 0;
  font-size: var(--vt-text-lg);
}
.title-input {
  width: min(480px, 100%);
  border: 0;
  padding: 0.2rem 0;
  color: var(--vt-text-primary);
  background: transparent;
  font: inherit;
  font-size: var(--vt-text-lg);
  font-weight: 700;
}
.code-editor {
  width: 100%;
  min-height: 390px;
  resize: vertical;
  border: 1px solid #334155;
  border-radius: 0.75rem;
  padding: 1rem;
  background: #0f172a;
  color: #e2e8f0;
  font:
    14px/1.65 "Cascadia Code",
    Consolas,
    monospace;
  tab-size: 4;
}
.test-table {
  display: grid;
  gap: 0.5rem;
}
.test-row {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(120px, 0.75fr) auto;
  gap: 0.5rem;
  align-items: center;
}
.test-row > button {
  border: 0;
  background: transparent;
  cursor: pointer;
  color: var(--vt-text-tertiary);
}
.test-labels {
  color: var(--vt-text-tertiary);
  font-size: var(--vt-text-xs);
}
.code-input {
  font-family: "Cascadia Code", Consolas, monospace;
}
.test-help,
.running-copy,
.empty-copy {
  margin: 0;
  color: var(--vt-text-secondary);
  line-height: 1.65;
}
.result-badge {
  border-radius: 999px;
  padding: 0.25rem 0.65rem;
  font-size: var(--vt-text-xs);
  background: rgba(15, 118, 110, 0.1);
  color: #0f766e;
}
.result-badge.error,
.result-badge.blocked,
.result-badge.timeout {
  background: rgba(239, 68, 68, 0.08);
  color: #b91c1c;
}
.test-results {
  display: grid;
  gap: 0.5rem;
}
.test-results > div {
  display: grid;
  gap: 0.2rem;
  padding: 0.65rem;
  border-radius: 0.6rem;
}
.test-results .passed {
  background: rgba(16, 185, 129, 0.08);
  color: #047857;
}
.test-results .failed {
  background: rgba(239, 68, 68, 0.07);
  color: #b91c1c;
}
.result-card pre {
  max-height: 330px;
  overflow: auto;
  margin: 0;
  padding: 0.8rem;
  border-radius: 0.6rem;
  background: #0f172a;
  color: #e2e8f0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.result-card .error-output {
  border-left: 4px solid #ef4444;
  color: #fecaca;
}
@media (max-width: 1220px) {
  .sandbox-layout {
    grid-template-columns: 220px minmax(0, 1fr);
  }
  .tutor-panel {
    grid-column: 1 / -1;
    position: static;
    max-height: none;
  }
}
@media (max-width: 760px) {
  .sandbox-header,
  .panel-heading {
    align-items: stretch;
    flex-direction: column;
  }
  .sandbox-layout {
    grid-template-columns: 1fr;
  }
  .history-panel {
    position: static;
    max-height: 360px;
  }
  .test-row {
    grid-template-columns: 1fr auto;
  }
  .test-row > :nth-child(2) {
    grid-column: 1;
  }
  .test-labels {
    display: none;
  }
  .code-editor {
    min-height: 320px;
  }
}
</style>
