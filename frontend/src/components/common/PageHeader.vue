<template>
  <header class="panel-header">
    <div class="panel-eyebrow">
      <span class="vt-eyebrow">
        <slot name="eyebrow" />
      </span>
      <slot name="badge" />
    </div>
    <h2 class="vt-subtitle">
      <slot name="title" />
    </h2>
    <p class="panel-desc">
      <slot name="desc" />
    </p>
  </header>
</template>

<script setup>
// Pure presentational, inherits all vt-* styles
</script>