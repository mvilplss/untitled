<script setup lang="ts">
withDefaults(
  defineProps<{
    variant?: 'default' | 'success' | 'warning' | 'danger' | 'primary'
  }>(),
  { variant: 'default' },
)
</script>

<template>
  <span class="tag-badge" :class="`tag-badge--${variant}`">
    <slot />
  </span>
</template>

<style scoped>
.tag-badge {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11.5px;
  background: var(--line-soft);
  color: var(--txt2);
  white-space: nowrap;
  font-family: var(--font-sans);
}
.tag-badge--default { background: var(--line-soft); color: var(--txt2); }
.tag-badge--success { background: var(--green-soft); color: var(--green); }
.tag-badge--warning { background: var(--amber-soft); color: var(--amber); }
.tag-badge--danger  { background: var(--red-soft);   color: var(--red); }
.tag-badge--primary { background: var(--primary-soft); color: var(--primary); }
</style>
