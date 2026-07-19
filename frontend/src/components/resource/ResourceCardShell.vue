<template>
  <article :class="classes">
    <slot />
  </article>
</template>

<script setup lang="ts">
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    artifactType?: string;
    variant?: "default" | "fresh" | "recommended";
  }>(),
  {
    artifactType: "",
    variant: "default",
  },
);

const classes = computed(() => [
  "resource-card",
  props.artifactType === "VIDEO_SCRIPT" ? "resource-card--video" : "",
  props.artifactType === "VISUALIZATION" ? "resource-card--visualization" : "",
  props.variant === "fresh" ? "fresh-card" : "",
  props.variant === "recommended" ? "recommended-card" : "",
]);
</script>
