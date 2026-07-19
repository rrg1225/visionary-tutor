<template>
  <section class="library-toolbar" aria-label="资源库检索与分类">
    <div class="toolbar-row">
      <div class="search-wrap">
        <label class="vt-label" for="resource-library-search">搜索资源</label>
        <input
          id="resource-library-search"
          v-model="localQuery"
          type="search"
          class="vt-input search-input"
          placeholder="搜索主题、标题、内容关键字…"
          autocomplete="off"
          data-testid="resource-library-search"
        />
      </div>
      <p class="toolbar-meta">
        共 <strong>{{ totalCount }}</strong> 项
        <span v-if="localQuery.trim() || typeFilter !== 'ALL'"> · 匹配 <strong>{{ resultCount }}</strong> 项</span>
      </p>
    </div>

    <div class="filter-chips" role="tablist" aria-label="资源类型筛选">
      <button
        v-for="chip in chips"
        :key="chip.type"
        type="button"
        role="tab"
        class="filter-chip"
        :class="{ active: typeFilter === chip.type, empty: !chip.count && chip.type !== 'ALL' }"
        :aria-selected="typeFilter === chip.type"
        :disabled="!chip.count && chip.type !== 'ALL'"
        @click="typeFilter = chip.type"
      >
        {{ chip.label }}
        <span v-if="chip.count" class="chip-count">{{ chip.count }}</span>
      </button>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { buildFilterChips, filterResources } from '../utils/resourceLibrary'

const localQuery = defineModel('query', { type: String, default: '' })
const typeFilter = defineModel('typeFilter', { type: String, default: 'ALL' })

const props = defineProps({
  resources: { type: Array, default: () => [] },
})

const chips = computed(() => buildFilterChips(props.resources, { query: localQuery.value }))
const totalCount = computed(() => props.resources.length)
const resultCount = computed(() => filterResources(props.resources, {
  query: localQuery.value,
  typeFilter: typeFilter.value,
}).length)
</script>

<style scoped>
.library-toolbar {
  display: grid;
  gap: var(--vt-space-3);
  padding: var(--vt-space-3);
  border: 1px solid var(--vt-border-light);
  border-radius: var(--vt-radius-md);
  background: var(--vt-bg-primary);
}

.toolbar-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--vt-space-3);
}

.search-wrap {
  flex: 1 1 280px;
  display: grid;
  gap: var(--vt-space-1);
}

.search-input {
  width: 100%;
}

.toolbar-meta {
  margin: 0;
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
}

.toolbar-meta strong {
  color: var(--vt-text-primary);
}

.filter-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vt-space-2);
}

.filter-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--vt-border-light);
  background: var(--vt-bg-secondary);
  font-size: var(--vt-text-xs);
  color: var(--vt-text-secondary);
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
}

.filter-chip:hover:not(:disabled) {
  border-color: var(--vt-accent-teal, #0d9488);
  color: var(--vt-text-primary);
}

.filter-chip.active {
  border-color: var(--vt-accent-teal, #0d9488);
  background: color-mix(in srgb, var(--vt-accent-teal, #0d9488) 12%, white);
  color: var(--vt-accent-teal, #0d9488);
  font-weight: var(--vt-font-semibold);
}

.filter-chip.empty:not(.active) {
  opacity: 0.45;
}

.chip-count {
  min-width: 1.25rem;
  padding: 0 5px;
  border-radius: 999px;
  background: rgb(0 0 0 / 0.06);
  font-size: 10px;
  line-height: 1.6;
  text-align: center;
}

.filter-chip.active .chip-count {
  background: rgb(13 148 136 / 0.15);
}
</style>
