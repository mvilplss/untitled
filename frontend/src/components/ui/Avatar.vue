<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    initial: string
    /** two-stop gradient */
    gradient?: [string, string]
    size?: 'sm' | 'md' | 'lg'
    title?: string
  }>(),
  { size: 'md' },
)

const fallbackGradients: [string, string][] = [
  ['#a5b4fc', '#6366f1'],
  ['#6ee7b7', '#059669'],
  ['#fcd34d', '#d97706'],
  ['#f9a8d4', '#db2777'],
  ['#93c5fd', '#2563eb'],
  ['#fdba74', '#ea580c'],
  ['#c4b5fd', '#7c3aed'],
  ['#5eead4', '#0d9488'],
]

function hashIndex(s: string): number {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return h % fallbackGradients.length
}

const g = computed<[string, string]>(() => {
  if (props.gradient) return props.gradient
  return fallbackGradients[hashIndex(props.initial || 'x')]
})

const style = computed(() => ({
  background: `linear-gradient(135deg, ${g.value[0]}, ${g.value[1]})`,
}))
</script>

<template>
  <div
    class="avatar"
    :class="`avatar--${size}`"
    :style="style"
    :title="title"
  >{{ initial }}</div>
</template>

<style scoped>
.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 650;
  letter-spacing: 0.5px;
  flex-shrink: 0;
  user-select: none;
}
.avatar--sm {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border-radius: 9px;
  font-size: 13px;
}
.avatar--md {
  width: 44px;
  height: 44px;
  flex: 0 0 44px;
  border-radius: 13px;
  font-size: 18px;
}
.avatar--lg {
  width: 62px;
  height: 62px;
  flex: 0 0 62px;
  border-radius: 18px;
  font-size: 26px;
}
</style>
