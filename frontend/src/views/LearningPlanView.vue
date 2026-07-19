<template>
  <ScopedResourceWorkspace
    workspace-id="learning-plan"
    eyebrow="学习规划"
    title="看清知识关系，也知道下一步学什么"
    description="知识导图回答“概念之间有什么关系”，学习路径回答“接下来按什么顺序学习”。两类规划资源在同一处维护。"
    :allowed-types="['MINDMAP', 'LEARNING_PATH']"
    :type-labels="{ MINDMAP: '知识导图', LEARNING_PATH: '学习路径' }"
    session-topic="学习规划"
    topic-placeholder="例如：为目标检测建立从 IoU、NMS 到 mAP 的学习顺序"
    resource-section-title="我的导图与学习路径"
    empty-title="还没有学习规划"
    empty-description="选择知识导图或学习路径，让系统把当前目标整理成可执行计划。"
  />
</template>

<script setup>
import ScopedResourceWorkspace from "../components/resource/ScopedResourceWorkspace.vue";
</script>
