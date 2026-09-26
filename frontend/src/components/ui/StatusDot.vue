<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    status?: 'online' | 'maintain' | 'offline'
    title?: string
  }>(),
  { status: 'online' },
)

const cls = computed(() => {
  if (props.status === 'online') return 'dot--on'
  if (props.status === 'maintain') return 'dot--mt'
  return 'dot--off'
})

const label = computed(() => {
  if (props.status === 'online') return '在线'
  if (props.status === 'maintain') return '维护中'
  return '已下线'
})
</script>

<template>
  <span class="status-dot">
    <span class="dot" :class="cls" :title="title || label"></span>
    <slot>{{ label }}</slot>
  </span>
</template>

<style scoped>
.status-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-12);
  color: var(--txt2);
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
}
.dot--on  { background: #16a34a; box-shadow: 0 0 0 3px rgba(22,163,74,.15); }
.dot--mt  { background: #d97706; box-shadow: 0 0 0 3px rgba(217,119,6,.15); }
.dot--off { background: #9aa0a6; box-shadow: 0 0 0 3px rgba(154,160,166,.15); }
</style>
