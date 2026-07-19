<template>
  <section class="lab-view">
    <div class="sandbox-entry vt-container vt-card">
      <div>
        <span class="vt-eyebrow">自由实验</span>
        <strong>已经有代码？进入独立沙箱直接运行和编写测试用例</strong>
      </div>
      <RouterLink class="vt-btn vt-btn-outline" to="/code-sandbox">
        打开代码沙箱
      </RouterLink>
    </div>
    <ScopedResourceWorkspace
      workspace-id="interactive-lab"
      eyebrow="互动实验"
      title="先观察变化，再用代码验证结果"
      description="动画实验负责解释参数变化与原理，代码实验负责运行和验证。两类实验共享学习主题，但不会混入讲义、题库或 PPT。"
      :allowed-types="['VISUALIZATION', 'CODE_PRACTICE']"
      allow-multi-select
      independent-type-actions
      history-enabled
      :type-labels="{ VISUALIZATION: '动画实验', CODE_PRACTICE: '代码实验' }"
      session-topic="互动实验"
      topic-placeholder="例如：调节 padding、stride，观察卷积输出尺寸并用 Python 验证"
      resource-section-title="我的动画与代码实验"
      empty-title="还没有互动实验"
      empty-description="选择动画实验或代码实验，创建一个可以动手验证的学习任务。"
    />
  </section>
</template>

<script setup>
import { RouterLink } from "vue-router";
import ScopedResourceWorkspace from "../components/resource/ScopedResourceWorkspace.vue";
</script>

<style scoped>
.lab-view {
  display: grid;
  gap: 1rem;
}
.sandbox-entry {
  max-width: 1480px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem 1.25rem;
}
.sandbox-entry div {
  display: grid;
  gap: 0.25rem;
}
@media (max-width: 640px) {
  .sandbox-entry {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
