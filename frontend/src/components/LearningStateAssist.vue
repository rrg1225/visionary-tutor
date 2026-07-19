<template>
  <section class="state-assist vt-card" :data-state="state">
    <header>
      <div>
        <span class="vt-eyebrow">学习状态辅助 · 可选</span>
        <h3>{{ stateTitle }}</h3>
      </div>
      <div class="assist-actions">
        <button v-if="cameraMounted" type="button" class="vt-btn vt-btn-outline vt-btn-sm" @click="stopAndAggregate">
          关闭并生成状态报告
        </button>
        <template v-else-if="canStart">
          <button type="button" class="vt-btn vt-btn-outline vt-btn-sm" @click="start('once')">临时开启</button>
          <button type="button" class="vt-btn vt-btn-primary vt-btn-sm" @click="start('session')">本次学习开启</button>
        </template>
      </div>
    </header>

    <p class="privacy-always">
      摄像头默认关闭；原始视频不上传、不保存。关闭后立即停止摄像头，仅把本机汇总结果写入当前学习记录。
      该功能只提供辅助信号，不判断真实情绪，也不影响成绩。
    </p>

    <div v-if="cameraMounted" class="capture-grid">
      <FaceCaptureStream
        :key="captureKey"
        :enabled="true"
        @ready="onReady"
        @error="onError"
        @sample="onSample"
        @intervention="$emit('intervention', $event)"
      />
      <dl>
        <div><dt>当前状态</dt><dd>{{ stateTitle }}</dd></div>
        <div><dt>有效样本</dt><dd>{{ samples.length }}</dd></div>
        <div><dt>运行时间</dt><dd>{{ elapsedSeconds }} 秒</dd></div>
      </dl>
    </div>

    <div v-if="report" class="state-report">
      <strong>{{ report.headline }}</strong>
      <p>{{ report.description }}</p>
      <dl>
        <div><dt>上下文</dt><dd>{{ report.contextTitle }}</dd></div>
        <div><dt>有效样本</dt><dd>{{ report.sampleCount }}</dd></div>
        <div><dt>观察时长</dt><dd>{{ report.durationSeconds }} 秒</dd></div>
        <div><dt>报告编号</dt><dd>{{ report.reportId }}</dd></div>
      </dl>
      <RouterLink class="vt-btn vt-btn-ghost vt-btn-sm" to="/learning-report">查看统一学习报告</RouterLink>
    </div>
    <p v-if="errorMessage" class="error" role="alert">{{ errorMessage }}</p>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import FaceCaptureStream from "./FaceCaptureStream.vue";
import { createLearningStateReport } from "../api/learningState";
import { useAuthStore } from "../stores/authStore";
import { useLearningSessionStore } from "../stores/learningSession";
import { getCameraSupportIssue } from "../utils/camera";

const props = defineProps({
  contextType: { type: String, default: "AI_TUTOR" },
  contextKey: { type: String, default: "main" },
  contextTitle: { type: String, default: "AI 辅导" },
  /**
   * 当前采样标记（如做题页传当前题目 ID）。样本按标记分组聚合后写入报告，
   * 供题卷报告做"困惑峰值 ↔ 失分题"交叉展示；不传则不分组。
   */
  sampleMarker: { type: String, default: "" },
});
const emit = defineEmits(["intervention", "report"]);
const authStore = useAuthStore();
const learningSession = useLearningSessionStore();
const state = ref("OFF");
const cameraMounted = ref(false);
const captureKey = ref(0);
const samples = ref([]);
const startedAt = ref(0);
const elapsedSeconds = ref(0);
const report = ref(null);
const errorMessage = ref("");
let timer = null;

const canStart = computed(() => ["OFF", "REPORT_READY", "INSUFFICIENT", "ERROR"].includes(state.value));
const stateTitle = computed(() => ({
  OFF: "未开启",
  REQUESTING_PERMISSION: "正在请求摄像头权限",
  CALIBRATING: "正在本地校准",
  OBSERVING: "正在本地观察",
  AGGREGATING: "正在汇总（摄像头已停止）",
  REPORT_READY: "状态报告已生成",
  INSUFFICIENT: "数据不足，未作状态判断",
  ERROR: "状态辅助不可用",
}[state.value] || state.value));

function start(mode = "once") {
  const issue = getCameraSupportIssue();
  if (issue) {
    state.value = "ERROR";
    errorMessage.value = issue.message;
    return;
  }
  report.value = null;
  errorMessage.value = "";
  samples.value = [];
  elapsedSeconds.value = 0;
  startedAt.value = Date.now();
  state.value = "REQUESTING_PERMISSION";
  if (mode === "session") sessionStorage.setItem("vt_learning_state_session_enabled", "1");
  captureKey.value += 1;
  cameraMounted.value = true;
  timer = globalThis.setInterval(() => { elapsedSeconds.value = Math.round((Date.now() - startedAt.value) / 1000); }, 1000);
}

function onReady() { state.value = "CALIBRATING"; }

function onSample(sample) {
  if (!["CALIBRATING", "OBSERVING"].includes(state.value)) return;
  samples.value.push({ ...sample, marker: props.sampleMarker || "" });
  if (samples.value.length >= 5) state.value = "OBSERVING";
}

function onError(message) {
  errorMessage.value = message || "摄像头或本地识别引擎不可用。";
  state.value = "ERROR";
  stopCameraNow();
}

function stopCameraNow() {
  cameraMounted.value = false;
  if (timer) globalThis.clearInterval(timer);
  timer = null;
  elapsedSeconds.value = startedAt.value ? Math.round((Date.now() - startedAt.value) / 1000) : 0;
}

function stopAndAggregate() {
  sessionStorage.removeItem("vt_learning_state_session_enabled");
  stopCameraNow();
  state.value = "AGGREGATING";
  queueMicrotask(buildReport);
}

function buildReport() {
  const valid = samples.value.filter((sample) => Number.isFinite(Number(sample.confusionScore)));
  const enough = valid.length >= 5 && elapsedSeconds.value >= 3;
  const scores = valid.map((sample) => Number(sample.confusionScore));
  const average = enough ? Math.round(scores.reduce((sum, value) => sum + value, 0) / scores.length) : null;
  const markers = enough ? aggregateMarkers(valid) : [];
  const result = {
    reportId: globalThis.crypto?.randomUUID?.() || `state-${Date.now()}`,
    userId: Number(authStore.currentUserId) || null,
    learningSessionId: learningSession.currentSessionId || null,
    contextType: props.contextType,
    contextKey: props.contextKey,
    contextTitle: props.contextTitle,
    sampleCount: valid.length,
    durationSeconds: elapsedSeconds.value,
    aggregateScore: average,
    sufficient: enough,
    markers,
    createdAt: new Date().toISOString(),
    headline: enough ? "本次仅记录到一个可供参考的视觉负荷信号" : "数据不足，未作状态判断",
    description: enough
      ? `本地汇总信号均值为 ${average}/100。它可能受光照、姿态、设备和个体差异影响，只用于提示是否考虑休息或换一种讲法。`
      : "有效样本或观察时长不足。系统不会把缺失数据解释为专注、困惑或任何情绪。",
  };
  const rows = JSON.parse(localStorage.getItem("vt_learning_state_reports") || "[]");
  localStorage.setItem("vt_learning_state_reports", JSON.stringify([result, ...rows].slice(0, 100)));
  // 注册用户：报告同步到服务端（问题21），报告中心与题卷报告可统一检索；
  // 上传的只有聚合指标与结论文案，隐私边界与页面承诺一致。
  if (authStore.isRegistered) {
    createLearningStateReport({
      learningSessionId: result.learningSessionId,
      contextType: result.contextType,
      contextKey: result.contextKey,
      contextTitle: result.contextTitle,
      sampleCount: result.sampleCount,
      durationSeconds: result.durationSeconds,
      aggregateScore: result.aggregateScore,
      sufficient: result.sufficient,
      headline: result.headline,
      description: result.description,
      markers: result.markers,
    }).catch(() => {
      // 服务端暂不可用时本机记录仍然完整，报告不丢失。
    });
  }
  report.value = result;
  state.value = enough ? "REPORT_READY" : "INSUFFICIENT";
  emit("report", result);
}

/** 按 sampleMarker（如题目 ID）分组，得出每个标记下的样本数与信号均值。 */
function aggregateMarkers(validSamples) {
  const groups = new Map();
  for (const sample of validSamples) {
    const marker = String(sample.marker || "").trim();
    if (!marker) continue;
    const group = groups.get(marker) || { marker, sampleCount: 0, total: 0 };
    group.sampleCount += 1;
    group.total += Number(sample.confusionScore);
    groups.set(marker, group);
  }
  return [...groups.values()]
    .filter((group) => group.sampleCount >= 3)
    .map((group) => ({
      marker: group.marker,
      sampleCount: group.sampleCount,
      averageScore: Math.round(group.total / group.sampleCount),
    }))
    .sort((left, right) => right.averageScore - left.averageScore)
    .slice(0, 50);
}

onMounted(() => {
  if (sessionStorage.getItem("vt_learning_state_session_enabled") === "1") start("session");
});
onBeforeUnmount(stopCameraNow);
</script>

<style scoped>
.state-assist { padding: 1rem; display: grid; gap: .75rem; }
.state-assist header { display: flex; justify-content: space-between; gap: .75rem; align-items: flex-start; }
.assist-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: .4rem; }
.state-assist h3 { margin: .2rem 0 0; font-size: 1rem; }
.privacy-always { margin: 0; padding: .7rem; border-radius: 8px; background: rgba(15,118,110,.055); color: var(--vt-text-secondary); font-size: .8rem; line-height: 1.55; }
.capture-grid { display: grid; gap: .65rem; }
.capture-grid dl, .state-report dl { display: grid; gap: .25rem; margin: 0; font-size: .78rem; }
.capture-grid dl div, .state-report dl div { display: grid; grid-template-columns: 90px 1fr; }
dt { color: var(--vt-text-secondary); } dd { margin: 0; overflow-wrap: anywhere; }
.state-report { display: grid; gap: .55rem; padding: .75rem; border-radius: 9px; background: rgba(79,70,229,.055); }
.state-report p { margin: 0; line-height: 1.55; font-size: .84rem; }
.error { color: #b91c1c; margin: 0; }
</style>
