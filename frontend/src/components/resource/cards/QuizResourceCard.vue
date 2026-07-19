<template>
  <QuizInteractive
    v-if="content"
    :content="content"
    :content-json="contentJson"
    :user-id="userId"
    :learning-session-id="learningSessionId"
    :on-submit="onSubmit"
    @submitted="(payload) => emit('submitted', payload)"
  />
</template>

<script setup lang="ts">
import QuizInteractive from "../../QuizInteractive.vue";

withDefaults(
  defineProps<{
    content?: string;
    contentJson?: string;
    userId?: number | null;
    learningSessionId?: number | null;
    onSubmit?: ((payload: unknown) => Promise<unknown>) | null;
  }>(),
  {
    content: "",
    contentJson: "",
    userId: null,
    learningSessionId: null,
    onSubmit: null,
  },
);

const emit = defineEmits<{ submitted: [payload: unknown] }>();
</script>
