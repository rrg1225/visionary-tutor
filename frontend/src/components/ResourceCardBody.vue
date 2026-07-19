<template>
  <div ref="cardRoot" class="resource-card-body">
    <div class="resource-head">
      <span class="resource-type">{{ item.type }}</span>
      <span
        class="resource-status"
        :class="statusClass(item.validationStatus)"
        >{{ item.status }}</span
      >
      <span
        v-if="
          item.artifactType === 'LEARNING_PATH' &&
          item.runId &&
          item.runId.startsWith('REPLAN-')
        "
        class="dynamic-badge"
        >动态调整</span
      >
      <span
        v-if="
          item.runId &&
          item.runId.startsWith('REPLAN-') &&
          item.artifactType !== 'LEARNING_PATH'
        "
        class="dynamic-badge"
        >专项推送</span
      >
      <ResourceOriginBadge :item="item" />
      <span v-if="hasSafetyBadge(item)" class="safety-badge"
        >✓ 内容已通过学术安全审查</span
      >
    </div>
    <h4>{{ item.title }}</h4>
    <p v-if="item.sessionTopic" class="resource-session-topic">
      学习主题：{{ item.sessionTopic }}
    </p>

    <p>{{ item.summary }}</p>

    <MermaidViewer
      v-if="item.artifactType === 'LEARNING_PATH' && pathMermaid"
      :content="pathMermaid"
    />

    <ol
      v-else-if="
        item.artifactType === 'LEARNING_PATH' && structuredPathSteps.length
      "
      class="path-steps interactive"
    >
      <li
        v-for="step in structuredPathSteps"
        :key="`path-step-${step.id || step.stepOrder}`"
        :class="['path-step-row', step.status]"
      >
        <span class="step-index">{{ step.stepOrder }}</span>
        <div class="step-body">
          <strong>{{ step.stepTitle }}</strong>
          <p v-if="step.stepGoal" class="step-goal">{{ step.stepGoal }}</p>
          <span v-if="step.estimatedMinutes" class="step-meta"
            >约 {{ step.estimatedMinutes }} 分钟</span
          >
        </div>
        <div class="step-actions">
          <button
            v-if="step.status === 'not_started'"
            type="button"
            class="vt-btn vt-btn-ghost vt-btn-sm"
            @click="setPathStatus(step.stepOrder, 'learning')"
          >
            开始
          </button>
          <button
            v-if="step.status === 'learning'"
            type="button"
            class="vt-btn vt-btn-primary vt-btn-sm"
            @click="setPathStatus(step.stepOrder, 'finished')"
          >
            完成
          </button>
          <button
            v-if="step.status !== 'finished' && step.status !== 'skipped'"
            type="button"
            class="vt-btn vt-btn-outline vt-btn-sm"
            @click="setPathStatus(step.stepOrder, 'skipped')"
          >
            跳过
          </button>
          <span v-if="step.status === 'finished'" class="step-done">✓</span>
        </div>
      </li>
    </ol>

    <ol
      v-else-if="
        item.artifactType === 'LEARNING_PATH' && pathSteps(item.content).length
      "
      class="path-steps"
    >
      <li
        v-for="(step, idx) in pathSteps(item.content)"
        :key="`${item.id}-step-${idx}`"
      >
        <span class="step-index">{{ idx + 1 }}</span>
        <span>{{ step }}</span>
      </li>
    </ol>

    <MindMapResourceCard
      v-if="item.artifactType === 'MINDMAP'"
      :content="item.content"
    />

    <QuizResourceCard
      v-if="item.artifactType === 'QUIZ'"
      :content="item.content"
      :content-json="item.contentJson"
      :user-id="userId"
      :learning-session-id="learningSessionId"
      :on-submit="submitQuiz"
      @submitted="(payload) => emit('quiz-submitted', payload)"
    />

    <DocumentResourceCard
      v-if="
        item.artifactType === 'HANDOUT' ||
        item.artifactType === 'EXTENDED_READING'
      "
      :content="item.content"
      :title="item.title"
      :enable-tutor="item.artifactType === 'EXTENDED_READING'"
      :learning-session-id="learningSessionId"
      :context-key="`resource:${item.id || item.runId || item.title}`"
    />

    <CodingResourceCard
      v-if="item.artifactType === 'CODE_PRACTICE'"
      :content="item.content"
      :loading="sandboxLoading"
      :passed="sandboxPassed"
      :failed="sandboxFailed"
      :unavailable="sandboxUnavailable"
      :status-label="sandboxStatusLabel"
      :error-log="sandboxErrorLog"
      :execution-time-ms="sandboxReport?.execution_time_ms"
      :output-log="sandboxOutputLog"
      :available="sandboxAvailable"
      :availability-message="sandboxAvailabilityMessage"
      :learning-session-id="learningSessionId"
      :context-key="`code-lab:${item.id || item.runId || item.title}`"
      @execute="runSandboxCode"
      @stop="stopSandboxCode"
    />

    <CnnConvolutionLab v-if="shouldShowCnnLab" />

    <div v-else-if="item.artifactType === 'VISUALIZATION'" class="viz-host">
      <header class="viz-toolbar">
        <div><strong>{{ item.title || '教学动画' }}</strong><small>{{ vizStepLabel }}</small></div>
        <div>
          <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm" @click="controlVisualization('PLAY')">开始</button>
          <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm" @click="controlVisualization('PAUSE')">暂停</button>
          <button type="button" class="vt-btn vt-btn-ghost vt-btn-sm" @click="controlVisualization('RESET')">重置</button>
          <button type="button" class="vt-btn vt-btn-outline vt-btn-sm" @click="fullscreenVisualization">全屏</button>
        </div>
      </header>
      <iframe
        v-if="vizIframeSrcdoc"
        ref="vizFrame"
        sandbox="allow-scripts"
        title="教学可视化"
        class="viz-frame"
        :srcdoc="vizIframeSrcdoc"
      />
      <pre v-else-if="item.content" class="code-block">{{ item.content }}</pre>
      <button type="button" class="vt-btn vt-btn-outline vt-btn-sm viz-tutor-button" @click="vizTutorOpen = !vizTutorOpen">
        {{ vizTutorOpen ? '收起步骤答疑' : '针对当前步骤问 AI 老师' }}
      </button>
      <ContextualTutorPanel
        v-if="vizTutorOpen"
        title="动画步骤 AI 老师"
        :context="vizTutorContext"
        :learning-session-id="learningSessionId"
        context-type="INTERACTIVE_LAB"
        :context-key="`visualization:${item.id || item.runId || item.title}`"
        :context-title="item.title || '教学动画'"
        suggested-question="请解释当前动画步骤，并告诉我参数变化会产生什么影响。"
        @close="vizTutorOpen = false"
      />
    </div>

    <ResourceActionBar
      :show="showActions"
      :can-read-aloud="canReadAloud"
      :tts-speaking="ttsSpeaking"
      :can-export-pptx="canExportPptx"
      :pptx-loading="pptxLoading"
      :show-visualization="item.artifactType === 'VISUALIZATION'"
      :viz-loading="vizLoading"
      @read-aloud="readAloud"
      @export-pptx="downloadPptx"
      @open-visualization="openVisualization"
    />

    <GovernanceTraceSection v-if="showGovernanceTrace" :artifact-id="item.id" />

    <details v-if="showFallbackDetail" class="resource-detail">
      <summary>查看原始内容</summary>
      <pre>{{ item.content }}</pre>
    </details>
  </div>
</template>

<script setup>
import {
  computed,
  defineAsyncComponent,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";

const MermaidViewer = defineAsyncComponent(() => import("./MermaidViewer.vue"));
const GovernanceTraceSection = defineAsyncComponent(
  () => import("./GovernanceTraceSection.vue"),
);
import ResourceActionBar from "./resource/ResourceActionBar.vue";
import ResourceOriginBadge from "./resource/ResourceOriginBadge.vue";
import CodingResourceCard from "./resource/cards/CodingResourceCard.vue";
import DocumentResourceCard from "./resource/cards/DocumentResourceCard.vue";
import MindMapResourceCard from "./resource/cards/MindMapResourceCard.vue";
import QuizResourceCard from "./resource/cards/QuizResourceCard.vue";
import CnnConvolutionLab from "./resource/cards/CnnConvolutionLab.vue";
import ContextualTutorPanel from "./ContextualTutorPanel.vue";
import {
  downloadArtifactPptx,
  formatApiErrorMessage,
  submitQuizResult,
} from "../api/resources";
import {
  fetchPathSteps,
  recordResourceUsage,
  updatePathStepStatus,
} from "../api/memory";
import { parseResourceContentEnvelope } from "../adapters/resource-content-adapter";
import { toastError, toastSuccess } from "../utils/toast";
import { useTextToSpeech } from "../composables/useTextToSpeech";
import { executeSandboxCode, fetchSandboxHealth } from "../api/sandbox";
import {
  isBrowserSandboxSupported,
  runPythonInBrowser,
  stopPythonInBrowser,
  warmupBrowserSandbox,
} from "../utils/pyodideSandbox";

const props = defineProps({
  item: { type: Object, required: true },
  userId: { type: Number, default: null },
  learningSessionId: { type: Number, default: null },
  showGovernanceTrace: { type: Boolean, default: false },
});

const emit = defineEmits(["quiz-submitted", "resource-updated"]);

const pptxLoading = ref(false);
const vizLoading = ref(false);
const vizIframeSrcdoc = ref("");
const vizFrame = ref(null);
const vizTutorOpen = ref(false);
const vizState = ref({ status: "READY", step: "" });
const structuredPathSteps = ref([]);
const viewRecorded = ref(false);
const cardRoot = ref(null);
let viewObserver = null;
let viewDwellTimer = null;
const sandboxLoading = ref(false);
const sandboxReport = ref(null);
const sandboxAvailable = ref(false);
const sandboxAvailabilityMessage = ref("正在检查代码沙箱…");
const {
  speak,
  isSpeaking: ttsSpeaking,
  supported: ttsSupported,
} = useTextToSpeech();

const sandboxPassed = computed(() => {
  const status = sandboxReport.value?.status;
  return status === "SUCCESS" || status === "PASSED";
});

const sandboxFailed = computed(() => {
  const status = sandboxReport.value?.status;
  return Boolean(status) && ["ERROR", "TIMEOUT", "BLOCKED"].includes(status);
});

const sandboxUnavailable = computed(
  () => sandboxReport.value?.status === "UNAVAILABLE",
);
const vizStepLabel = computed(() => vizState.value.step ? `当前步骤：${vizState.value.step}` : `状态：${vizState.value.status}`);
const vizTutorContext = computed(() => [
  `动画主题：${props.item.title || '教学动画'}`,
  `当前状态：${vizState.value.status}`,
  vizState.value.step ? `当前步骤：${vizState.value.step}` : '',
  `动画说明：${String(props.item.content || '').slice(0, 10000)}`,
].filter(Boolean).join('\n\n'));

const sandboxStatusLabel = computed(() => {
  const status = sandboxReport.value?.status;
  if (status === "TIMEOUT") return "执行超时";
  if (status === "BLOCKED") return "代码被拦截";
  return "沙箱执行失败";
});

const sandboxErrorLog = computed(() => {
  const report = sandboxReport.value;
  if (!report) return "";
  const parts = [report.error, report.output].filter(
    (line) => line && String(line).trim(),
  );
  return parts.join("\n\n") || "未返回错误详情";
});

const sandboxOutputLog = computed(() => {
  const report = sandboxReport.value;
  if (!report || sandboxFailed.value || sandboxUnavailable.value) return "";
  return [report.output, report.stdout]
    .filter((line) => line && String(line).trim())
    .join("\n");
});

const canReadAloud = computed(() => {
  if (!ttsSupported.value) return false;
  return (
    Boolean(props.item.content) &&
    ["HANDOUT", "EXTENDED_READING"].includes(props.item.artifactType)
  );
});

// MINDMAP / LEARNING_PATH 是图形资源，PPTX 里只会变成干瘪的文字页，
// 且线上 Python 缺失时导出体验差——改为 MermaidViewer 自带的 SVG/PNG 下载。
const canExportPptx = computed(
  () =>
    ["HANDOUT", "QUIZ"].includes(props.item.artifactType) &&
    props.item.id != null &&
    Number.isFinite(Number(props.item.id)) &&
    props.item.publishStatus !== "BLOCKED",
);

const shouldShowCnnLab = computed(() => {
  if (props.item.artifactType !== "VISUALIZATION") return false;
  const text = [
    props.item.title,
    props.item.summary,
    props.item.content,
    props.item.sessionTopic,
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
  return /cnn|卷积|padding|stride|feature.?map/.test(text);
});

const showActions = computed(
  () =>
    canExportPptx.value ||
    props.item.artifactType === "VISUALIZATION" ||
    canReadAloud.value,
);

const contentEnvelope = computed(() =>
  parseResourceContentEnvelope(props.item.contentJson),
);

const pathGraph = computed(() => {
  if (props.item.artifactType !== "LEARNING_PATH" || !props.item.contentJson)
    return null;
  return contentEnvelope.value.graph || null;
});

const pathMermaid = computed(() => pathGraph.value?.mermaid || "");

const showFallbackDetail = computed(() => {
  const type = props.item.artifactType;
  if (
    type === "HANDOUT" ||
    type === "EXTENDED_READING" ||
    type === "CODE_PRACTICE"
  )
    return false;
  if (type === "VISUALIZATION" && vizIframeSrcdoc.value) return false;
  return Boolean(props.item.content);
});

function hasSafetyBadge(item) {
  const envelope = parseResourceContentEnvelope(item?.contentJson);
  return (
    envelope.content_safety === "PASSED" &&
    !item?.degraded &&
    item?.publishStatus !== "DEGRADED" &&
    item?.publishStatus !== "BLOCKED"
  );
}

function statusClass(status) {
  return (
    {
      GROUNDED: "status-grounded",
      NO_EVIDENCE: "status-model",
      RAG_UNUSED: "status-model",
      WEAK_GROUNDING: "status-warning",
    }[status] || ""
  );
}

function pathSteps(content = "") {
  return content
    .split("\n")
    .map((line) => line.trim())
    .filter(Boolean)
    .slice(0, 8);
}

function readAloud() {
  const text = [props.item.title, props.item.summary, props.item.content]
    .filter(Boolean)
    .join("。");
  void speak(text).then((ok) => {
    if (!ok) {
      toastError("朗读失败，请确认后端 TTS 已配置（DashScope / 讯飞）");
      return;
    }
  });
}

async function submitQuiz(payload) {
  return submitQuizResult(payload);
}

async function downloadPptx() {
  if (pptxLoading.value || !props.item.id) return;
  pptxLoading.value = true;
  try {
    const blob = await downloadArtifactPptx(props.item.id);
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `visionary-${props.item.artifactType?.toLowerCase()}-${props.item.id}.pptx`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    toastSuccess("PPTX 已导出");
  } catch (err) {
    toastError(`PPTX 导出失败：${formatApiErrorMessage(err, "网络错误")}`);
    console.error("[downloadPptx]", err);
  } finally {
    pptxLoading.value = false;
  }
}

function openVisualization() {
  vizLoading.value = true;
  const raw = props.item.content?.trim() || "";
  if (
    raw &&
    (raw.includes("echarts") ||
      raw.includes("<script") ||
      raw.includes("<div") ||
      raw.includes("<html"))
  ) {
    vizIframeSrcdoc.value = hardenVisualizationDocument(raw);
  }
  vizLoading.value = false;
  emit("resource-updated", props.item);
}

function hardenVisualizationDocument(raw) {
  const csp = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data:; font-src data:">`;
  const bridge = `<style>html.visionary-paused *,html.visionary-paused *::before,html.visionary-paused *::after{animation-play-state:paused!important}</style><script>(function(){function emit(status,step){parent.postMessage({type:'visionary:state',status:status,step:step||''},'*')}addEventListener('message',function(e){var m=e.data||{};if(m.type!=='visionary:control')return;if(m.action==='PAUSE'){document.documentElement.classList.add('visionary-paused');emit('PAUSED')}if(m.action==='PLAY'){document.documentElement.classList.remove('visionary-paused');emit('PLAYING')}if(m.action==='RESET'){emit('RESETTING');location.reload()}});addEventListener('load',function(){emit('READY')})})();<\/script>`;
  if (/<head[\s>]/i.test(raw))
    return raw.replace(/<head([^>]*)>/i, `<head$1>${csp}${bridge}`);
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">${csp}${bridge}<meta name="viewport" content="width=device-width,initial-scale=1"></head><body>${raw}</body></html>`;
}

function controlVisualization(action) {
  vizFrame.value?.contentWindow?.postMessage({ type: "visionary:control", action }, "*");
}

async function fullscreenVisualization() {
  if (vizFrame.value?.requestFullscreen) await vizFrame.value.requestFullscreen();
}

function handleVisualizationMessage(event) {
  if (event.source !== vizFrame.value?.contentWindow || event.data?.type !== "visionary:state") return;
  vizState.value = { status: event.data.status || "READY", step: event.data.step || "" };
}

async function loadStructuredPathSteps() {
  if (props.item.artifactType !== "LEARNING_PATH" || !props.learningSessionId) {
    structuredPathSteps.value = [];
    return;
  }
  try {
    structuredPathSteps.value = await fetchPathSteps(props.learningSessionId);
  } catch {
    structuredPathSteps.value = [];
  }
}

async function setPathStatus(stepOrder, status) {
  if (!props.learningSessionId) return;
  try {
    await updatePathStepStatus(props.learningSessionId, stepOrder, status);
    await loadStructuredPathSteps();
  } catch (err) {
    toastError(err?.message || "路径状态更新失败");
  }
}

async function runSandboxValidation() {
  if (props.item.artifactType !== "CODE_PRACTICE") return;
  // 主路径：浏览器内 Pyodide 沙箱（无需服务端 Docker/Python，手机端同样可用）
  if (isBrowserSandboxSupported()) {
    sandboxAvailable.value = true;
    sandboxAvailabilityMessage.value = "";
    warmupBrowserSandbox();
    return;
  }
  // 兜底：极少数不支持 WebAssembly 的浏览器，退回服务端沙箱
  const health = await fetchSandboxHealth();
  sandboxAvailable.value = Boolean(health?.available);
  sandboxAvailabilityMessage.value = sandboxAvailable.value
    ? ""
    : health?.message || "代码沙箱暂不可用，运行入口已关闭";
}

async function runSandboxCode(code) {
  if (!code?.trim() || sandboxLoading.value || !sandboxAvailable.value) return;
  sandboxLoading.value = true;
  sandboxReport.value = null;
  try {
    if (isBrowserSandboxSupported()) {
      const report = await runPythonInBrowser(code);
      // 浏览器沙箱启动失败（如运行时 CDN 不可达）时，尝试服务端沙箱兜底
      if (report.status === "UNAVAILABLE") {
        const health = await fetchSandboxHealth().catch(() => null);
        if (health?.available) {
          sandboxReport.value = await executeSandboxCode(code);
          return;
        }
      }
      sandboxReport.value = report;
      return;
    }
    sandboxReport.value = await executeSandboxCode(code);
  } catch (err) {
    sandboxReport.value = {
      status: "UNAVAILABLE",
      error: err?.message || "沙箱服务请求失败",
      output: "",
      execution_time_ms: 0,
    };
  } finally {
    sandboxLoading.value = false;
  }
}

function stopSandboxCode() {
  stopPythonInBrowser();
  sandboxLoading.value = false;
  sandboxReport.value = {
    status: "UNAVAILABLE",
    error: "用户已停止本次执行",
    output: "",
    execution_time_ms: 0,
  };
}

async function recordViewUsage() {
  if (
    viewRecorded.value ||
    !props.userId ||
    !props.learningSessionId ||
    !props.item?.id ||
    props.item?.isShowcase
  )
    return;
  viewRecorded.value = true;
  try {
    await recordResourceUsage({
      userId: props.userId,
      learningSessionId: props.learningSessionId,
      resourceId: props.item.id,
      actionType: "view",
      durationSeconds: null,
      feedback: null,
    });
  } catch {
    // non-blocking
  }
}

watch(
  () => [props.item?.id, props.learningSessionId, props.item?.artifactType],
  () => {
    loadStructuredPathSteps();
  },
  { immediate: true },
);

watch(
  () => [props.item?.id, props.item?.content, props.item?.artifactType],
  () => {
    if (props.item?.artifactType === "CODE_PRACTICE") {
      runSandboxValidation();
    } else {
      sandboxReport.value = null;
    }
  },
  { immediate: true },
);

onMounted(() => {
  window.addEventListener("message", handleVisualizationMessage);
  if (!cardRoot.value || typeof IntersectionObserver === "undefined") return;
  viewObserver = new IntersectionObserver(
    (entries) => {
      const visible = entries.some(
        (entry) => entry.isIntersecting && entry.intersectionRatio >= 0.6,
      );
      if (visible && !viewDwellTimer) {
        viewDwellTimer = window.setTimeout(() => {
          viewDwellTimer = null;
          void recordViewUsage();
        }, 3000);
      } else if (!visible && viewDwellTimer) {
        window.clearTimeout(viewDwellTimer);
        viewDwellTimer = null;
      }
    },
    { threshold: [0.6] },
  );
  viewObserver.observe(cardRoot.value);
});

onBeforeUnmount(() => {
  window.removeEventListener("message", handleVisualizationMessage);
  if (sandboxLoading.value) stopPythonInBrowser();
  viewObserver?.disconnect();
  if (viewDwellTimer) window.clearTimeout(viewDwellTimer);
});
</script>

<style scoped>
.resource-card-body {
  display: grid;
  gap: var(--vt-space-2);
}
.resource-head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
  align-items: center;
}
.resource-type,
.resource-status {
  font-size: 10px;
  font-weight: var(--vt-font-semibold);
  color: var(--vt-text-tertiary);
}
.status-grounded {
  color: var(--vt-accent-teal);
}
.status-model {
  color: var(--vt-text-secondary);
}
.status-warning {
  color: var(--vt-accent-orange);
}
.dynamic-badge,
.safety-badge,
.publish-badge,
.showcase-badge,
.provenance-badge {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 9999px;
  background: #dcfce7;
  color: #166534;
}
.showcase-badge {
  background: rgba(99, 102, 241, 0.14);
  color: #4338ca;
}
.provenance-badge.live {
  background: #dcfce7;
  color: #166534;
}
.publish-badge.degraded {
  background: #fef3c7;
  color: #92400e;
}
h4 {
  margin: 0;
  font-size: var(--vt-text-sm);
}
.resource-session-topic {
  margin: 0;
  font-size: 10px;
  color: var(--vt-text-tertiary);
}
p {
  margin: 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
}
.path-steps {
  display: grid;
  gap: var(--vt-space-1);
  padding: 0;
  list-style: none;
}
.path-steps.interactive {
  gap: var(--vt-space-2);
}
.path-step-row {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: var(--vt-space-2);
  align-items: start;
  padding: var(--vt-space-2);
  border-radius: var(--vt-radius-sm);
  border: 1px solid var(--vt-border-subtle);
}
.path-step-row.finished {
  opacity: 0.85;
  background: rgba(46, 125, 50, 0.06);
}
.path-step-row.learning {
  border-color: var(--vt-accent-teal, #0d9488);
}
.step-body {
  display: grid;
  gap: 2px;
}
.step-goal,
.step-meta {
  margin: 0;
  font-size: 10px;
  color: var(--vt-text-tertiary);
}
.step-actions {
  display: flex;
  gap: 4px;
  align-items: center;
}
.step-done {
  color: var(--vt-accent-teal);
  font-weight: 700;
}
.path-steps li {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--vt-space-2);
  font-size: var(--vt-text-sm);
}
.step-index {
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: rgba(59, 130, 246, 0.12);
}
.markdown-body {
  font-size: var(--vt-text-xs);
  line-height: 1.55;
  max-height: 220px;
  overflow: auto;
}
.code-practice-block {
  display: grid;
  gap: var(--vt-space-2);
}
.code-block {
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  font-size: var(--vt-text-xs);
  background: var(--vt-bg-secondary);
  padding: var(--vt-space-2);
  border-radius: var(--vt-radius-md);
}
.sandbox-status.loading {
  font-size: var(--vt-text-xs);
  color: var(--vt-text-tertiary);
}
.sandbox-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 10px;
  font-weight: var(--vt-font-semibold);
  padding: 2px 8px;
  border-radius: 9999px;
  width: fit-content;
}
.sandbox-badge.passed {
  background: #dcfce7;
  color: #166534;
}
.sandbox-badge.failed {
  background: #fee2e2;
  color: #991b1b;
}
.sandbox-badge.unavailable {
  background: #fef3c7;
  color: #92400e;
}
.sandbox-error-panel {
  display: grid;
  gap: var(--vt-space-1);
}
.sandbox-error-head {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
  align-items: center;
}
.sandbox-meta {
  font-size: 10px;
  color: var(--vt-text-tertiary);
}
.sandbox-error-log {
  margin: 0;
  max-height: 160px;
  overflow: auto;
  white-space: pre-wrap;
  font-size: var(--vt-text-xs);
  background: #fff1f2;
  color: #9f1239;
  padding: var(--vt-space-2);
  border-radius: var(--vt-radius-md);
  border: 1px solid #fecdd3;
}
.viz-host { display: grid; gap: .75rem; }
.viz-toolbar { display: flex; align-items: center; justify-content: space-between; gap: .75rem; }
.viz-toolbar > div { display: flex; flex-wrap: wrap; align-items: center; gap: .4rem; }
.viz-toolbar > div:first-child { display: grid; }
.viz-toolbar small { color: var(--vt-text-tertiary); }
.viz-tutor-button { justify-self: start; }
.viz-frame {
  width: 100%;
  height: 320px;
  border: 0;
  border-radius: var(--vt-radius-md);
  background: #fff;
}
@media (max-width: 640px) { .viz-toolbar { align-items: stretch; flex-direction: column; } .viz-frame { height: 420px; } }
.resource-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}
.resource-detail pre {
  max-height: 180px;
  overflow: auto;
  white-space: pre-wrap;
  font-size: var(--vt-text-xs);
}
</style>
