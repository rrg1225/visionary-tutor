<template>
  <MermaidViewer v-if="content" :content="content" />
</template>

<script setup lang="ts">
import { defineAsyncComponent } from "vue";

withDefaults(defineProps<{ content?: string }>(), { content: "" });
const MermaidViewer = defineAsyncComponent(
  () => import("../../MermaidViewer.vue"),
);
</script>
