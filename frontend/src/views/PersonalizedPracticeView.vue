<template>
  <main class="practice-page">
    <LearningStateAssist
      context-type="PERSONALIZED_PRACTICE"
      context-key="generated-quiz"
      context-title="AI 个性化专项练习"
    />
    <ScopedResourceWorkspace
      workspace-id="personalized-practice"
      eyebrow="AI 个性化专项练习"
      title="围绕一个薄弱点生成专项题"
      description="这里只生成专项练习。平台精选综合题卷和系统内容自测使用固定且经过审核的题目，不会在作答中途临时改题。"
      :allowed-types="['QUIZ']"
      :type-labels="{ QUIZ: '专项练习' }"
      session-topic="AI 个性化专项练习"
      topic-placeholder="例如：围绕 padding 与卷积输出尺寸生成 6 道递进练习"
      resource-section-title="我的 AI 专项练习"
      empty-title="还没有专项练习"
      empty-description="填写需要强化的知识点，生成一组只针对当前薄弱点的练习。"
    />
  </main>
</template>

<script setup>
import LearningStateAssist from "../components/LearningStateAssist.vue";
import ScopedResourceWorkspace from "../components/resource/ScopedResourceWorkspace.vue";
</script>

<style scoped>
.practice-page { max-width: 1240px; margin: 0 auto; padding: 1rem; display: grid; gap: 1rem; }
</style>
