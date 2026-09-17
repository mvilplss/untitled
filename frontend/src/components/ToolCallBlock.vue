<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ToolCall } from '@/types/api'

const props = defineProps<{
  tc: ToolCall
  /** 整条消息是否仍在流式累积（用于打 streaming 角标） */
  streaming?: boolean
  /**
   * 上层容器（ReasoningPanel level=2 时）强制展开 args/output。
   * 为 true 时 caret 固定显示 ▾，且点击不再切换。
   */
  forceOpen?: boolean
}>()

const open = ref(false)

/** 最终是否展开内容：forceOpen 优先级最高 */
const visible = computed(() => props.forceOpen || open.value)

const stateClass = computed(() => {
  const s = props.tc.state || 'RUNNING'
  return `tool-call__state--${s.toLowerCase()}`
})

const stateLabel = computed(() => {
  const s = props.tc.state || (props.streaming ? 'RUNNING' : 'SUCCESS')
  return s
})

const argsPretty = computed(() => {
  const raw = (props.tc.arguments || '').trim()
  if (!raw) return ''
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
})

const outputText = computed(() => (props.tc.output || '').trim())

function toggle() {
  if (props.forceOpen) return
  open.value = !open.value
}
</script>

<template>
  <div class="tool-call" :class="{ 'tool-call--forced': forceOpen }">
    <button
      type="button"
      class="tool-call__handle mono"
      :aria-expanded="visible"
      @click="toggle"
    >
      <span class="tool-call__caret">{{ visible ? '▾' : '▸' }}</span>
      <span class="tool-call__name">{{ tc.name || '（未命名）' }}</span>
      <span class="tool-call__state" :class="stateClass">{{ stateLabel }}</span>
      <span v-if="streaming && (!tc.state || tc.state === 'RUNNING')" class="tool-call__streaming mono">
        <span class="tool-call__pulse"></span>
        生成中
      </span>
    </button>

    <div v-if="visible" class="tool-call__body">
      <div v-if="argsPretty" class="tool-call__section">
        <div class="tool-call__label mono">// 参数</div>
        <pre class="tool-call__pre mono">{{ argsPretty }}</pre>
      </div>
      <div v-if="outputText" class="tool-call__section">
        <div class="tool-call__label mono">// 输出</div>
        <pre class="tool-call__pre tool-call__pre--output mono">{{ outputText }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-call {
  margin: 0;
  border: 1px solid var(--border);
  background: var(--bg-surface);
}
.tool-call--forced { border-color: var(--border); }

.tool-call__handle {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  background: transparent;
  border: none;
  border-bottom: 1px solid transparent;
  cursor: pointer;
  text-align: left;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-mute);
  transition: color 0.1s ease, border-color 0.1s ease;
}
.tool-call__handle:hover { color: var(--text); border-bottom-color: var(--border); }
.tool-call__caret {
  display: inline-block;
  width: 10px;
  color: var(--text-faint);
  font-size: 11px;
}
.tool-call__name {
  font-family: var(--font-mono);
  color: var(--text);
  font-weight: 500;
}
.tool-call__state {
  font-size: 10px;
  padding: 1px 6px;
  border: 1px solid currentColor;
  font-family: var(--font-mono);
}
.tool-call__state--success { color: var(--ok); }
.tool-call__state--error { color: var(--err); }
.tool-call__state--interrupted,
.tool-call__state--denied { color: var(--text-mute); }
.tool-call__state--running { color: var(--signal); }

.tool-call__streaming {
  margin-left: auto;
  color: var(--signal);
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.tool-call__pulse {
  width: 5px;
  height: 5px;
  background: var(--signal);
  display: inline-block;
  animation: uac-blink 1s steps(1) infinite;
}

.tool-call__body {
  padding: 6px 10px 8px;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: var(--bg-base);
}
.tool-call__section { display: flex; flex-direction: column; gap: 4px; }
.tool-call__label {
  color: var(--text-mute);
  font-size: 11px;
}
.tool-call__pre {
  margin: 0;
  padding: 6px 10px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  font-size: 12px;
  line-height: var(--lh-base);
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 320px;
  overflow: auto;
}
.tool-call__pre--output {
  border-left: 2px solid var(--signal-2);
}
</style>
