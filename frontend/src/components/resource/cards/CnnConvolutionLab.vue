<template>
  <section class="cnn-lab" aria-label="CNN 卷积交互实验">
    <header class="lab-header">
      <div>
        <span class="lab-kicker">INTERACTIVE LAB</span>
        <h5>CNN 卷积滑窗实验</h5>
        <p>调整 padding 与 stride，逐步观察卷积核如何产生特征图。</p>
      </div>
      <div class="formula">
        O = floor(({{ input.length }} - {{ kernel.length }} + 2×{{ padding }}) /
        {{ stride }}) + 1
        <strong>= {{ result.outputSize }}</strong>
      </div>
    </header>

    <div class="controls">
      <label>
        Padding
        <input v-model.number="padding" type="range" min="0" max="2" step="1" />
        <output>{{ padding }}</output>
      </label>
      <label>
        Stride
        <input v-model.number="stride" type="range" min="1" max="2" step="1" />
        <output>{{ stride }}</output>
      </label>
      <button
        type="button"
        class="vt-btn vt-btn-primary vt-btn-sm"
        @click="advance"
      >
        下一步 {{ currentStep + 1 }}/{{ result.positions.length }}
      </button>
      <button
        type="button"
        class="vt-btn vt-btn-ghost vt-btn-sm"
        @click="playing = !playing"
      >
        {{ playing ? "暂停" : "自动播放" }}
      </button>
      <button
        type="button"
        class="vt-btn vt-btn-outline vt-btn-sm"
        @click="reset"
      >
        重置
      </button>
      <button
        type="button"
        class="vt-btn vt-btn-outline vt-btn-sm"
        @click="previous"
      >
        上一步
      </button>
      <label>
        进度
        <input
          v-model.number="currentStep"
          class="progress-control"
          type="range"
          min="0"
          :max="Math.max(0, result.positions.length - 1)"
          step="1"
        />
        <output>{{ currentStep + 1 }}/{{ result.positions.length }}</output>
      </label>
      <label>
        速度
        <select v-model.number="speedMs">
          <option :value="1200">慢速</option>
          <option :value="700">标准</option>
          <option :value="350">快速</option>
        </select>
      </label>
    </div>

    <div class="lab-stage">
      <div class="matrix-panel">
        <span class="matrix-label">输入（含 padding）</span>
        <div class="matrix input-matrix" :style="gridStyle(result.paddedInput)">
          <span
            v-for="cell in inputCells"
            :key="`input-${cell.row}-${cell.col}`"
            :class="[
              'cell',
              {
                active: isActiveInput(cell.row, cell.col),
                padded: cell.padded,
              },
            ]"
            >{{ cell.value }}</span
          >
        </div>
      </div>

      <div class="operation-panel" aria-live="polite">
        <span>3×3 边缘检测核</span>
        <div class="matrix kernel-matrix" :style="gridStyle(kernel)">
          <span
            v-for="(value, index) in kernel.flat()"
            :key="`kernel-${index}`"
            class="cell kernel-cell"
            >{{ value }}</span
          >
        </div>
        <strong v-if="activePosition"
          >当前位置输出：{{ activePosition.value }}</strong
        >
      </div>

      <div class="matrix-panel">
        <span class="matrix-label">输出特征图</span>
        <div class="matrix output-matrix" :style="gridStyle(result.output)">
          <span
            v-for="cell in outputCells"
            :key="`output-${cell.row}-${cell.col}`"
            :class="[
              'cell',
              {
                revealed: cell.index <= currentStep,
                current: cell.index === currentStep,
              },
            ]"
            >{{ cell.index <= currentStep ? cell.value : "·" }}</span
          >
        </div>
      </div>
    </div>

    <footer class="lab-insight">
      <strong>观察：</strong>
      padding={{ padding }} 时输入边界{{
        padding ? "被零填充并参与计算" : "不会被补充"
      }}；stride={{ stride }} 使卷积核每次移动 {{ stride }} 格，因此输出为
      {{ result.outputSize }}×{{ result.outputSize }}。
    </footer>
    <section class="step-explanation" aria-live="polite">
      <strong>当前步骤解释</strong>
      <p v-if="activePosition">
        卷积核左上角位于第 {{ activePosition.row + 1 }} 行、第
        {{ activePosition.col + 1 }} 列； 当前窗口逐元素相乘后求和得到
        {{ activePosition.value }}，写入输出特征图的第
        {{ currentStep + 1 }} 个位置。
      </p>
      <p v-else>当前参数下没有可计算位置，请调整 padding 或 stride。</p>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { convolve2d, type Matrix } from "../../../domain/cnn-convolution";

const input: Matrix = [
  [1, 1, 1, 0, 0],
  [0, 1, 1, 1, 0],
  [0, 0, 1, 1, 1],
  [0, 0, 1, 1, 0],
  [0, 1, 1, 0, 0],
];
const kernel: Matrix = [
  [1, 0, -1],
  [1, 0, -1],
  [1, 0, -1],
];

const padding = ref(1);
const stride = ref(1);
const currentStep = ref(0);
const playing = ref(false);
const speedMs = ref(700);
let timer: number | undefined;

const result = computed(() =>
  convolve2d(input, kernel, { padding: padding.value, stride: stride.value }),
);
const activePosition = computed(
  () => result.value.positions[currentStep.value],
);
const inputCells = computed(() =>
  result.value.paddedInput.flatMap((row, rowIndex) =>
    row.map((value, colIndex) => ({
      row: rowIndex,
      col: colIndex,
      value,
      padded:
        rowIndex < padding.value ||
        colIndex < padding.value ||
        rowIndex >= result.value.paddedInput.length - padding.value ||
        colIndex >= result.value.paddedInput.length - padding.value,
    })),
  ),
);
const outputCells = computed(() =>
  result.value.output.flatMap((row, rowIndex) =>
    row.map((value, colIndex) => ({
      row: rowIndex,
      col: colIndex,
      value,
      index: rowIndex * result.value.outputSize + colIndex,
    })),
  ),
);

function gridStyle(matrix: Matrix) {
  return {
    gridTemplateColumns: `repeat(${matrix[0]?.length || 1}, minmax(28px, 1fr))`,
  };
}

function isActiveInput(row: number, col: number) {
  const active = activePosition.value;
  return (
    Boolean(active) &&
    row >= active.row &&
    row < active.row + kernel.length &&
    col >= active.col &&
    col < active.col + kernel.length
  );
}

function advance() {
  const count = result.value.positions.length;
  currentStep.value = count ? (currentStep.value + 1) % count : 0;
}

function previous() {
  const count = result.value.positions.length;
  currentStep.value = count ? (currentStep.value - 1 + count) % count : 0;
}

function reset() {
  currentStep.value = 0;
  playing.value = false;
}

function stopTimer() {
  if (timer !== undefined) window.clearInterval(timer);
  timer = undefined;
}

watch([padding, stride], reset);
watch([playing, speedMs], ([enabled]) => {
  stopTimer();
  if (enabled) timer = window.setInterval(advance, speedMs.value);
});
onBeforeUnmount(stopTimer);
</script>

<style scoped>
.cnn-lab {
  container-type: inline-size;
  display: grid;
  gap: 16px;
  padding: 18px;
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  border: 1px solid rgba(13, 148, 136, 0.22);
  border-radius: 16px;
  background: linear-gradient(145deg, #f0fdfa, #f8fafc 45%, #eef2ff);
  box-shadow: 0 16px 35px rgba(15, 23, 42, 0.08);
}
.lab-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
}
.lab-kicker {
  font-size: 10px;
  letter-spacing: 0.16em;
  font-weight: 800;
  color: #0f766e;
}
h5 {
  margin: 4px 0;
  font-size: 18px;
  color: #0f172a;
}
p {
  margin: 0;
  color: #475569;
}
.formula {
  max-width: min(330px, 100%);
  min-width: 0;
  overflow-wrap: anywhere;
  padding: 10px 14px;
  border-radius: 10px;
  background: #0f172a;
  color: #cbd5e1;
  font:
    12px/1.5 ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
}
.formula strong {
  color: #5eead4;
  margin-left: 6px;
}
.controls {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}
.controls label {
  display: flex;
  gap: 6px;
  align-items: center;
  font-size: 12px;
  font-weight: 700;
  color: #334155;
}
.controls input {
  width: 86px;
  accent-color: #0d9488;
}
.controls output {
  min-width: 20px;
  text-align: center;
  color: #0f766e;
}
.controls select {
  padding: 4px 6px;
  border: 1px solid #cbd5e1;
  border-radius: 7px;
  background: white;
}
.step-explanation {
  padding: 12px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid rgba(99, 102, 241, 0.16);
}
.step-explanation p {
  margin-top: 5px;
  line-height: 1.6;
}
.lab-stage {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 0.65fr) minmax(0, 1fr);
  gap: 18px;
  align-items: center;
}
.matrix-panel,
.operation-panel {
  display: grid;
  justify-items: center;
  gap: 8px;
  min-width: 0;
}
.matrix-label,
.operation-panel > span {
  font-size: 11px;
  font-weight: 700;
  color: #475569;
}
.matrix {
  display: grid;
  gap: 3px;
  width: min(100%, 300px);
  min-width: 0;
  max-width: 100%;
}
.cell {
  aspect-ratio: 1;
  display: grid;
  place-items: center;
  min-width: 0;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid #cbd5e1;
  font:
    600 clamp(9px, 2.4cqw, 12px) ui-monospace,
    SFMono-Regular,
    Consolas,
    monospace;
  transition: 0.18s ease;
}
.cell.padded {
  color: #94a3b8;
  background: rgba(226, 232, 240, 0.65);
}
.cell.active {
  background: #ccfbf1;
  border-color: #14b8a6;
  color: #115e59;
  transform: scale(1.04);
}
.kernel-cell {
  background: #312e81;
  border-color: #6366f1;
  color: white;
}
.output-matrix .cell {
  color: #94a3b8;
}
.output-matrix .cell.revealed {
  color: #1e3a8a;
  background: #dbeafe;
  border-color: #60a5fa;
}
.output-matrix .cell.current {
  color: white;
  background: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.18);
}
.operation-panel strong {
  font-size: 12px;
  color: #4338ca;
}
.lab-insight {
  padding: 10px 12px;
  border-left: 3px solid #14b8a6;
  background: rgba(255, 255, 255, 0.72);
  color: #334155;
  font-size: 12px;
  line-height: 1.6;
}
@container (max-width: 720px) {
  .lab-header {
    display: grid;
  }
  .lab-stage {
    grid-template-columns: 1fr;
  }
  .formula {
    max-width: none;
  }
}

@media (max-width: 640px) {
  .cnn-lab {
    padding: 12px;
  }
}
</style>
