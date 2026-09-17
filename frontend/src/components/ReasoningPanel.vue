<script setup lang="ts">
import { computed, ref } from 'vue'
import ToolCallBlock from '@/components/ToolCallBlock.vue'
import type { ToolCall } from '@/types/api'

const props = defineProps<{
  /** 推理过程文本（可空） */
  thinking?: string
  /** 工具调用列表（可空） */
  toolCalls?: ToolCall[]
  /** 整条消息是否仍在流式累积（用于打 streaming 角标） */
  streaming?: boolean
}>()

/**
 * 状态机：正交 2-state 设计
 *   masterOpen=false                       → L1 单行摘要
 *   masterOpen=true, subs 全关             → L2 主头 + 两个分区头
 *   masterOpen=true, ≥1 sub 开             → L3 分区头 + 对应内容展开
 */
const masterOpen = ref(false)
const thinkingOpen = ref(false)
const execOpen = ref(false)

const hasThinking = computed(() => !!(props.thinking && props.thinking.length > 0))
const hasExecution = computed(() => !!(props.toolCalls && props.toolCalls.length > 0))
const showPanel = computed(() => hasThinking.value || hasExecution.value)

const thinkingLines = computed(() => (props.thinking || '').split('\n').length)
const thinkingChars = computed(() => (props.thinking || '').length)
const toolCount = computed(() => props.toolCalls?.length ?? 0)

function toggleMaster() {
  masterOpen.value = !masterOpen.value
}
function toggleThinking() {
  thinkingOpen.value = !thinkingOpen.value
}
function toggleExec() {
  execOpen.value = !execOpen.value
}

async function copyThinking() {
  try {
    await navigator.clipboard.writeText(props.thinking || '')
  } catch {
    /* ignore */
  }
}
</script>

<template>
  <div v-if="showPanel" class="reasoning">
    <!-- ============ Master handle ============ -->
    <button
      type="button"
      class="reasoning__master mono"
      :aria-expanded="masterOpen"
      @click="toggleMaster"
    >
      <span class="reasoning__caret">{{ masterOpen ? '▾' : '▸' }}</span>
      <span class="reasoning__master-label">思考 + 执行</span>
      <span class="reasoning__master-count">
        <template v-if="hasThinking">
          <span class="mono">{{ thinkingLines }} 行</span>
          <span class="reasoning__sep">/</span>
          <span class="mono">{{ thinkingChars }} 字符</span>
        </template>
        <template v-if="hasThinking && hasExecution">
          <span class="reasoning__sep reasoning__sep--bar">|</span>
        </template>
        <template v-if="hasExecution">
          <span class="mono">{{ toolCount }} 个工具</span>
        </template>
      </span>
      <span class="reasoning__spacer"></span>
      <span v-if="props.streaming && hasExecution" class="reasoning__streaming mono">
        <span class="reasoning__pulse"></span>
        生成中
      </span>
      <button
        v-if="hasThinking"
        type="button"
        class="reasoning__copy mono"
        @click.stop="copyThinking"
      >
        复制
      </button>
    </button>

    <!-- ============ Sub sections (only when master open) ============ -->
    <div v-if="masterOpen" class="reasoning__subs">
      <!-- thinking -->
      <div v-if="hasThinking" class="reasoning__sub">
        <button
          type="button"
          class="reasoning__sub-head mono"
          :aria-expanded="thinkingOpen"
          @click="toggleThinking"
        >
          <span class="reasoning__caret reasoning__caret--sub">
            {{ thinkingOpen ? '▾' : '▸' }}
          </span>
          <span>思考过程</span>
          <span class="reasoning__sub-count mono">
            {{ thinkingLines }} 行 / {{ thinkingChars }} 字符
          </span>
        </button>
        <pre v-if="thinkingOpen" class="reasoning__thinking-body mono">{{ props.thinking }}</pre>
      </div>

      <!-- execution -->
      <div v-if="hasExecution" class="reasoning__sub">
        <button
          type="button"
          class="reasoning__sub-head mono"
          :aria-expanded="execOpen"
          @click="toggleExec"
        >
          <span class="reasoning__caret reasoning__caret--sub">
            {{ execOpen ? '▾' : '▸' }}
          </span>
          <span>工具执行</span>
          <span class="reasoning__sub-count mono">
            {{ toolCount }} 个工具
          </span>
        </button>
        <div v-if="execOpen" class="reasoning__exec-body">
          <ToolCallBlock
            v-for="tc in props.toolCalls"
            :key="tc.id"
            :tc="tc"
            :streaming="props.streaming"
            :force-open="execOpen"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.reasoning {
  margin: 6px 0 10px;
  border-left: 2px solid var(--signal-2);
  background: var(--bg-surface);
}

/* ============ Master ============ */
.reasoning__master {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px;
  background: transparent;
  border: none;
  border-bottom: 1px solid transparent;
  cursor: pointer;
  text-align: left;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-mute);
  transition: border-color 0.1s ease, color 0.1s ease;
}
.reasoning__master:hover {
  color: var(--text);
  border-bottom-color: var(--border);
}
.reasoning__master[aria-expanded="true"] {
  border-bottom-color: var(--border);
}
.reasoning__caret {
  display: inline-block;
  width: 10px;
  color: var(--signal-2);
  font-size: 11px;
}
.reasoning__master-label {
  color: var(--text);
  font-weight: 500;
}
.reasoning__master-count {
  color: var(--text-faint);
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.reasoning__sep {
  color: var(--border);
}
.reasoning__sep--bar {
  margin: 0 4px;
}
.reasoning__spacer { flex: 1; }
.reasoning__streaming {
  color: var(--signal);
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.reasoning__pulse {
  width: 5px;
  height: 5px;
  background: var(--signal);
  display: inline-block;
  animation: uac-blink 1s steps(1) infinite;
}
.reasoning__copy {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-mute);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
  transition: color 0.1s ease;
}
.reasoning__copy:hover {
  color: var(--signal);
}

/* ============ Subs ============ */
.reasoning__subs {
  display: flex;
  flex-direction: column;
}
.reasoning__sub {
  border-bottom: 1px solid var(--border-soft);
}
.reasoning__sub:last-child { border-bottom: none; }

.reasoning__sub-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px 4px 24px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-mute);
  background: transparent;
  border: none;
  cursor: pointer;
  text-align: left;
  transition: color 0.1s ease;
}
.reasoning__sub-head:hover { color: var(--text); }
.reasoning__sub-head > span:nth-child(2) { color: var(--text); font-weight: 500; }
.reasoning__caret--sub {
  color: var(--text-faint);
  width: 10px;
  font-size: 11px;
}
.reasoning__sub-count {
  color: var(--text-faint);
  font-size: 11px;
  margin-left: auto;
}

.reasoning__thinking-body {
  margin: 0;
  padding: 10px 14px 12px 32px;
  font-size: 12px;
  line-height: var(--lh-base);
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
  background: var(--bg-base);
  border-top: 1px solid var(--border-soft);
}

.reasoning__exec-body {
  padding: 8px 12px 12px 24px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: var(--bg-base);
  border-top: 1px solid var(--border-soft);
}
</style>
