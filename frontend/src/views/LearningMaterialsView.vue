<template>
  <ScopedResourceWorkspace
    workspace-id="learning-materials"
    eyebrow="我的学习资料"
    title="讲义与深度阅读，各自回到阅读任务"
    description="在这里生成和阅读个性化讲义或深度阅读材料。列表只展示标题、主题和相关内容，不混入题库、实验、导图或成果导出。"
    :allowed-types="['HANDOUT', 'EXTENDED_READING']"
    :type-labels="{ HANDOUT: '个性化讲义', EXTENDED_READING: '深度阅读' }"
    session-topic="个性化学习资料"
    topic-placeholder="例如：从卷积输出尺寸到感受野，整理一份适合复习的讲义"
    resource-section-title="我的讲义与深度阅读"
    empty-title="还没有学习资料"
    empty-description="选择讲义或深度阅读，填写主题后创建第一份资料。"
  />
</template>

<script setup>
import ScopedResourceWorkspace from "../components/resource/ScopedResourceWorkspace.vue";
</script>
