<script setup lang="ts">
import { computed } from 'vue'
import type { UsageStats } from '@/types/api'

const props = defineProps<{ usages: UsageStats[] }>()

interface Totals {
  inputTokens: number
  outputTokens: number
  cachedTokens: number
  totalTokens: number
  time: number
}

const totals = computed<Totals>(() =>
  props.usages.reduce<Totals>(
    (acc, u) => ({
      inputTokens: acc.inputTokens + (u.inputTokens ?? 0),
      outputTokens: acc.outputTokens + (u.outputTokens ?? 0),
      cachedTokens: acc.cachedTokens + (u.cachedTokens ?? 0),
      totalTokens: acc.totalTokens + (u.totalTokens ?? 0),
      time: acc.time + (u.time ?? 0),
    }),
    { inputTokens: 0, outputTokens: 0, cachedTokens: 0, totalTokens: 0, time: 0 },
  ),
)

/** 千分位整数（无小数） */
function fmtInt(n: number): string {
  return Math.round(n ?? 0).toLocaleString('en-US')
}

/** 缓存率（缓存/总计），保留 1 位小数；分母为 0 时显示 0.0% */
const cacheRateText = computed(() => {
  const t = totals.value.totalTokens
  if (t <= 0) return '0.0%'
  return `${((totals.value.cachedTokens / t) * 100).toFixed(1)}%`
})

/** 耗时：<60s 显示「2.8秒」，≥60s 显示「XmYs」整数秒（先 round 再拆，避免秒位进位到 60） */
const timeText = computed(() => {
  const total = totals.value.time
  if (!isFinite(total) || total < 0) return '0秒'
  if (total < 60) return `${total.toFixed(1)}秒`
  const totalSec = Math.round(total)
  const minutes = Math.floor(totalSec / 60)
  const seconds = totalSec % 60
  return `${minutes}m${seconds}s`
})
</script>

<template>
  <div v-if="usages.length" class="usage mono">
    用量：{{ usages.length }}次调用，输入 {{ fmtInt(totals.inputTokens) }}
    输出 {{ fmtInt(totals.outputTokens) }}
    缓存 {{ fmtInt(totals.cachedTokens) }}
    缓存率 {{ cacheRateText }}，
    总计 {{ fmtInt(totals.totalTokens) }}，
    {{ timeText }}
  </div>
</template>

<style scoped>
.usage {
  margin-top: 6px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--txt3);
  white-space: normal;
  word-break: break-word;
}
</style>