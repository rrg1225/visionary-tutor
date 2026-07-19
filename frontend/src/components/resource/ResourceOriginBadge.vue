<template>
  <span
    class="resource-origin-badge"
    :class="`resource-origin-badge--${presentation.tone}`"
    :title="presentation.title"
  >
    {{ presentation.label }}
  </span>
</template>

<script setup lang="ts">
import { computed } from "vue";
import {
  resolveResourceOrigin,
  type ResourceProvenance,
} from "../../domain/resource-origin";

const props = defineProps<{ item: ResourceProvenance }>();
const presentation = computed(() => resolveResourceOrigin(props.item));
</script>

<style scoped>
.resource-origin-badge {
  border-radius: 999px;
  display: inline-flex;
  font-size: 0.72rem;
  font-weight: 700;
  line-height: 1;
  padding: 0.3rem 0.52rem;
}
.resource-origin-badge--live {
  background: #dcfce7;
  color: #166534;
}
.resource-origin-badge--degraded {
  background: #fef3c7;
  color: #92400e;
}
.resource-origin-badge--demo {
  background: #e0e7ff;
  color: #3730a3;
}
.resource-origin-badge--neutral {
  background: #f1f5f9;
  color: #475569;
}
</style>
