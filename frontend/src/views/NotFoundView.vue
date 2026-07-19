<template>
  <section class="not-found vt-container-narrow vt-card-elevated" aria-labelledby="not-found-title">
    <span class="error-code" aria-hidden="true">404</span>
    <div>
      <span class="vt-eyebrow">页面未找到</span>
      <h1 id="not-found-title" class="vt-title">这个学习页面不存在</h1>
      <p class="vt-text-muted">
        地址可能已变更或输入有误。你可以返回学习循环，或回到首页重新开始。
      </p>
      <code class="missing-path">{{ route.fullPath }}</code>
    </div>
    <div class="not-found-actions">
      <RouterLink class="vt-btn vt-btn-primary" to="/learn">返回学习循环</RouterLink>
      <RouterLink class="vt-btn vt-btn-outline" to="/">返回首页</RouterLink>
    </div>
  </section>
</template>

<script setup>
import { RouterLink, useRoute } from 'vue-router'

const route = useRoute()
</script>

<style scoped>
.not-found {
  display: grid;
  gap: var(--vt-space-5);
  margin: clamp(48px, 10vh, 112px) auto;
  padding: clamp(var(--vt-space-6), 7vw, var(--vt-space-10));
}

.error-code {
  color: var(--vt-accent-teal);
  font-size: clamp(3.5rem, 14vw, 7rem);
  font-weight: 800;
  line-height: 0.9;
  opacity: 0.22;
}

.missing-path {
  display: block;
  overflow-wrap: anywhere;
  margin-top: var(--vt-space-4);
  padding: var(--vt-space-2) var(--vt-space-3);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-secondary);
  color: var(--vt-text-secondary);
}

.not-found-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-3);
}
</style>
