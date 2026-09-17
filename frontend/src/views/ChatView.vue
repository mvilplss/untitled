<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { marked } from 'marked'
import { useAgentsStore } from '@/stores/agents'
import { useChatStore } from '@/stores/chat'
import ReasoningPanel from '@/components/ReasoningPanel.vue'
import type { ChatMessage } from '@/types/api'

const agentsStore = useAgentsStore()
const chatStore = useChatStore()

const input = ref('')
const scrollEl = ref<HTMLElement>()
const agentPickerOpen = ref(false)
const agentPickerEl = ref<HTMLElement | null>(null)

onMounted(() => agentsStore.fetchList())

watch(() => agentsStore.list, async (list) => {
  if (!chatStore.agentId && list.length > 0) {
    await chatStore.setAgentId(list[0].id)
  } else if (chatStore.agentId) {
    await chatStore.loadSessions(chatStore.agentId)
  }
})

watch(
  () =>
    chatStore.messages.length +
    chatStore.messages.map(m =>
      (m.content || '') +
      (m.thinking || '') +
      (m.toolCalls || []).map(t => (t.name || '') + (t.arguments || '') + (t.output || '')).join(''),
    ).join('').length,
  () => nextTick(scrollToBottom),
  { flush: 'post' },
)
watch(() => chatStore.sessionId, () => nextTick(scrollToBottom))

function scrollToBottom() {
  if (scrollEl.value) {
    scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  }
}

async function handleSend() {
  const text = input.value.trim()
  if (!text || !chatStore.canSend) return
  input.value = ''
  await chatStore.send(text)
}

function handleKeyDown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    handleSend()
  }
}

function handleClear() {
  input.value = ''
}

function handleStop() {
  chatStore.cancelStream()
}

function handleNewConversation() {
  chatStore.newConversation()
}

function handleSwitchSession(sid: string) {
  chatStore.switchSession(sid)
}

async function handleDeleteSession(sid: string) {
  await chatStore.deleteSession(sid)
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    /* ignore */
  }
}

function fmtTime(ts: number): string {
  const d = new Date(ts)
  return [d.getHours(), d.getMinutes(), d.getSeconds()]
    .map(n => (n < 10 ? `0${n}` : `${n}`))
    .join(':')
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`
}

function renderMarkdown(text: string): string {
  marked.setOptions({ breaks: true, gfm: true })
  return marked.parse(text || '') as string
}

const selectedAgent = computed(() =>
  agentsStore.list.find(a => a.id === chatStore.agentId) ?? null,
)

const showEmpty = computed(() => !chatStore.messages.length && !chatStore.loadingHistory)

function toggleAgentPicker() {
  if (!agentsStore.list.length) return
  agentPickerOpen.value = !agentPickerOpen.value
}

function pickAgent(id: string) {
  if (id === chatStore.agentId) {
    agentPickerOpen.value = false
    return
  }
  chatStore.setAgentId(id, { force: true })
  agentPickerOpen.value = false
}

function onDocClick(e: MouseEvent) {
  if (!agentPickerOpen.value) return
  const el = agentPickerEl.value
  if (el && !el.contains(e.target as Node)) {
    agentPickerOpen.value = false
  }
}

function onDocKey(e: KeyboardEvent) {
  if (e.key === 'Escape' && agentPickerOpen.value) {
    agentPickerOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onDocKey)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onDocKey)
})
</script>

<template>
  <div class="chat-view">
    <aside class="chat-sidebar">
      <div class="chat-sidebar__block">
        <div class="chat-sidebar__block-head mono">// 数字人</div>
        <div v-if="!agentsStore.list.length" class="chat-sidebar__hint mono">
          // 暂无数字人 — 请到
          <router-link to="/digital-humans">数字人管理</router-link>
          创建
        </div>
        <div v-else ref="agentPickerEl" class="agent-picker">
          <button
            type="button"
            class="agent-picker__trigger mono"
            :class="{ 'agent-picker__trigger--open': agentPickerOpen }"
            @click="toggleAgentPicker"
          >
            <span class="agent-picker__dot"></span>
            <span class="agent-picker__id">{{ selectedAgent?.id || '—' }}</span>
            <span class="agent-picker__caret">{{ agentPickerOpen ? '▴' : '▾' }}</span>
          </button>

          <div v-if="agentPickerOpen" class="agent-picker__menu" role="listbox">
            <div class="agent-picker__menu-head mono">
              // 已选 · {{ pad2(agentsStore.list.length) }}
            </div>
            <button
              v-for="a in agentsStore.list"
              :key="a.id"
              type="button"
              class="agent-picker__item"
              :class="{ 'agent-picker__item--active': a.id === chatStore.agentId }"
              role="option"
              :aria-selected="a.id === chatStore.agentId"
              @click="pickAgent(a.id)"
            >
              <span class="agent-picker__item-dot"></span>
              <span class="agent-picker__item-main">
                <span class="agent-picker__item-id mono">{{ a.id }}</span>
                <span v-if="a.name && a.name !== a.id" class="agent-picker__item-name">{{ a.name }}</span>
              </span>
              <span class="agent-picker__item-model mono">{{ a.modelName }}</span>
            </button>
          </div>
        </div>
        <button class="chat-sidebar__btn mono" @click="handleNewConversation" :disabled="!chatStore.agentId">
          + 新建对话
        </button>
      </div>

      <div class="chat-sidebar__block chat-sidebar__block--grow">
        <div class="chat-sidebar__block-head mono">
          <span>// 历史会话</span>
          <span class="chat-sidebar__count">{{ pad2(chatStore.sessions.length) }}</span>
        </div>
        <div class="chat-sidebar__sessions" v-loading="chatStore.loadingSessions">
          <div v-if="!chatStore.sessions.length" class="chat-sidebar__hint chat-sidebar__hint--block mono">
            // 暂无会话
          </div>
          <div
            v-for="s in chatStore.sessions"
            :key="s.sessionId"
            class="session"
            :class="{ 'session--active': s.sessionId === chatStore.sessionId }"
            @click="handleSwitchSession(s.sessionId)"
          >
            <div class="session__row1">
              <span class="session__sid mono">{{ s.sessionId.slice(0, 14) }}</span>
              <span class="session__preview">{{ s.preview || '（无预览）' }}</span>
            </div>
            <div class="session__row2 mono">
              <span>{{ s.messageCount }} 条消息</span>
              <span class="session__sep">·</span>
              <span>{{ chatStore.formatRelative(s.lastActive) }}</span>
              <span class="session__spacer"></span>
              <button class="session__del" @click.stop="handleDeleteSession(s.sessionId)" title="删除会话">
                ×
              </button>
            </div>
          </div>
        </div>
      </div>

      <div class="chat-sidebar__block">
        <div class="chat-sidebar__block-head mono">// 用户标识</div>
        <el-input v-model="chatStore.userId" placeholder="匿名" size="small" />
      </div>

      <div class="chat-sidebar__block">
        <div class="chat-sidebar__block-head mono">// 模式</div>
        <el-radio-group v-model="chatStore.mode" size="small">
          <el-radio-button value="stream">流式</el-radio-button>
          <el-radio-button value="sync">同步</el-radio-button>
        </el-radio-group>
      </div>
    </aside>

    <section class="chat-main">
      <div class="chat-meta">
        <div class="chat-meta__cell">
          <span class="chat-meta__label mono">数字人</span>
          <span class="chat-meta__val mono">{{ chatStore.agentId || '—' }}</span>
        </div>
        <div class="chat-meta__cell">
          <span class="chat-meta__label mono">会话</span>
          <span class="chat-meta__val mono">{{ chatStore.sessionId || '—' }}</span>
        </div>
        <div class="chat-meta__cell">
          <span class="chat-meta__label mono">模式</span>
          <span class="chat-meta__val mono">{{ chatStore.mode === 'stream' ? '流式' : '同步' }}</span>
        </div>
        <div class="chat-meta__cell">
          <span class="chat-meta__label mono">消息数</span>
          <span class="chat-meta__val mono">{{ pad2(chatStore.messages.length) }}</span>
        </div>
        <div v-if="chatStore.streaming" class="chat-meta__cell chat-meta__cell--active">
          <span class="chat-meta__pulse"></span>
          <span class="chat-meta__val mono">生成中</span>
        </div>
      </div>

      <div ref="scrollEl" class="chat-transcript" v-loading="chatStore.loadingHistory">
        <div v-if="showEmpty" class="chat-empty">
          <div class="chat-empty__line mono">
            <template v-if="chatStore.agentId">
              // 准备就绪 — 输入消息后按 ↵ 发送
            </template>
            <template v-else>
              // 请先选择或创建数字人
            </template>
          </div>
        </div>

        <div v-else class="chat-rows">
          <div
            v-for="m in chatStore.messages"
            :key="m.id"
            class="chat-row"
            :class="['chat-row--' + m.role, m.streaming && 'chat-row--streaming']"
          >
            <div class="chat-row__time mono">{{ fmtTime(m.createdAt) }}</div>
            <div class="chat-row__role mono">
              {{ m.role === 'user' ? '用户' : '数字人' }}
            </div>
            <div class="chat-row__body">
              <ReasoningPanel
                v-if="m.role === 'assistant'"
                :thinking="m.thinking"
                :tool-calls="m.toolCalls"
                :streaming="m.toolCallsStreaming"
              />
              <div
                v-if="m.content || m.streaming"
                class="chat-row__content"
                v-html="renderMarkdown(m.content)"
              ></div>
              <span v-if="m.streaming" class="uac-cursor">▍</span>
              <div v-if="m.content && !m.streaming" class="chat-row__actions">
                <button class="chat-row__action mono" @click="copyText(m.content)">复制</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="chat-input">
        <textarea
          v-model="input"
          class="chat-input__textarea mono"
          rows="3"
          :placeholder="chatStore.agentId ? '// 输入消息 — ↵ 发送' : '// 请先选择数字人'"
          :disabled="!chatStore.agentId"
          @keydown="handleKeyDown"
        ></textarea>
        <div class="chat-input__bar">
          <span class="chat-input__meta mono">
            {{ pad2(input.length) }} 字符
          </span>
          <span class="chat-input__sep mono">·</span>
          <span class="chat-input__meta mono">模式：{{ chatStore.mode === 'stream' ? '流式' : '同步' }}</span>
          <span class="chat-input__spacer"></span>
          <button
            v-if="chatStore.streaming"
            class="btn-ghost mono chat-input__btn"
            @click="handleStop"
          >
            停止
          </button>
          <button class="btn-ghost mono chat-input__btn" @click="handleClear" :disabled="!chatStore.messages.length">
            清空
          </button>
          <button
            class="btn-primary chat-input__btn"
            :disabled="!input.trim() || !chatStore.agentId || chatStore.loading"
            @click="handleSend"
          >
            发送
            <span class="chat-input__enter mono">↵</span>
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.chat-view {
  display: grid;
  grid-template-columns: 264px 1fr;
  grid-template-rows: 100%;
  height: calc(100vh - 48px);
  margin: -24px;
  background: var(--bg-base);
  border-top: 1px solid var(--border);
  overflow: hidden;
}

/* ============ sidebar ============ */
.chat-sidebar {
  border-right: 1px solid var(--border);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow: hidden;
  min-height: 0;
  background: var(--bg-surface);
}
.chat-sidebar__block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.chat-sidebar__block--grow { flex: 1; min-height: 0; }
.chat-sidebar__block-head {
  font-size: 11px;
  color: var(--text-mute);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--border-soft);
}
.chat-sidebar__count { color: var(--text-faint); }
.chat-sidebar__hint {
  font-size: 12px;
  color: var(--text-mute);
  line-height: 1.5;
  padding: 4px 0;
}
.chat-sidebar__hint a {
  color: var(--signal-2);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.chat-sidebar__hint--block {
  padding: 12px 8px;
  text-align: center;
  border: 1px dashed var(--border);
  font-size: 11px;
  color: var(--text-faint);
}

/* ============ agent picker ============ */
.agent-picker { position: relative; }
.agent-picker__trigger {
  width: 100%;
  display: grid;
  grid-template-columns: 10px 1fr 14px;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  cursor: pointer;
  font-size: 12px;
  color: var(--text);
  text-align: left;
  transition: border-color 0.1s ease;
}
.agent-picker__trigger:hover { border-color: var(--text-mute); }
.agent-picker__trigger--open { border-color: var(--signal); }
.agent-picker__dot {
  width: 6px;
  height: 6px;
  background: var(--ok);
  display: inline-block;
}
.agent-picker__id {
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.agent-picker__caret {
  color: var(--text-mute);
  font-size: 11px;
  text-align: right;
}

.agent-picker__menu {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  z-index: var(--z-dropdown);
  background: var(--bg-elevated);
  border: 1px solid var(--signal);
  max-height: 360px;
  overflow-y: auto;
}
.agent-picker__menu-head {
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
  font-size: 11px;
  color: var(--text-mute);
}
.agent-picker__item {
  width: 100%;
  display: grid;
  grid-template-columns: 8px 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: transparent;
  border: none;
  border-bottom: 1px solid var(--border-soft);
  cursor: pointer;
  font-family: inherit;
  font-size: 12px;
  text-align: left;
  color: var(--text-mute);
  transition: background-color 0.08s ease, color 0.08s ease;
}
.agent-picker__item:last-child { border-bottom: none; }
.agent-picker__item:hover { background: var(--bg-surface); color: var(--text); }
.agent-picker__item--active {
  background: var(--bg-surface);
  color: var(--text);
  border-left: 2px solid var(--signal);
  padding-left: 8px;
}
.agent-picker__item-dot {
  width: 6px;
  height: 6px;
  background: var(--border);
  display: inline-block;
}
.agent-picker__item--active .agent-picker__item-dot { background: var(--ok); }
.agent-picker__item-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.agent-picker__item-id {
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.agent-picker__item-name {
  font-size: 11px;
  color: var(--text-faint);
}
.agent-picker__item-model {
  font-size: 11px;
  color: var(--signal-2);
  border: 1px solid var(--signal-2);
  padding: 1px 6px;
  white-space: nowrap;
}

.chat-sidebar__btn {
  font-family: var(--font-mono);
  font-size: 12px;
  background: transparent;
  border: 1px solid var(--border);
  padding: 6px 10px;
  text-align: left;
  cursor: pointer;
  color: var(--text-mute);
  transition: border-color 0.1s ease, color 0.1s ease;
}
.chat-sidebar__btn:hover:not(:disabled) {
  border-color: var(--text-mute);
  color: var(--text);
}
.chat-sidebar__btn:disabled { opacity: 0.4; cursor: not-allowed; }

.chat-sidebar__sessions {
  flex: 1;
  overflow-y: auto;
  margin-top: 6px;
}

.session {
  padding: 6px 8px;
  border-bottom: 1px solid var(--border-soft);
  cursor: pointer;
  transition: background-color 0.08s ease;
}
.session:hover { background: var(--bg-elevated); }
.session--active {
  background: var(--bg-elevated);
  border-left: 2px solid var(--signal);
  padding-left: 6px;
}
.session__row1 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text);
  margin-bottom: 3px;
  white-space: nowrap;
  overflow: hidden;
}
.session__sid {
  font-size: 11px;
  color: var(--text-mute);
  flex-shrink: 0;
}
.session__session--active .session__sid { color: var(--signal); }
.session__preview {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
}
.session__row2 {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--text-faint);
}
.session__sep { color: var(--border); }
.session__spacer { flex: 1; }
.session__del {
  font-family: var(--font-mono);
  background: transparent;
  border: none;
  color: var(--text-faint);
  cursor: pointer;
  padding: 0 4px;
  font-size: 14px;
  line-height: 1;
}
.session__del:hover { color: var(--err); }

/* ============ main column ============ */
.chat-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--bg-base);
}

/* ============ meta strip ============ */
.chat-meta {
  display: flex;
  align-items: center;
  gap: 0;
  padding: 8px 20px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border);
  font-size: 11px;
  flex-shrink: 0;
}
.chat-meta__cell {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  border-right: 1px solid var(--border-soft);
}
.chat-meta__cell:last-child { border-right: none; }
.chat-meta__cell--active { color: var(--signal); }
.chat-meta__label {
  color: var(--text-mute);
}
.chat-meta__val {
  color: var(--text);
}
.chat-meta__cell--active .chat-meta__val { color: var(--signal); }
.chat-meta__pulse {
  width: 6px;
  height: 6px;
  background: var(--signal);
  display: inline-block;
  animation: uac-blink 1s steps(1) infinite;
}

/* ============ transcript ============ */
.chat-transcript {
  flex: 1 1 0;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 20px;
}
.chat-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.chat-empty__line {
  font-size: 13px;
  color: var(--text-faint);
}

.chat-rows {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.chat-row {
  display: grid;
  grid-template-columns: 76px 56px 1fr;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--border-soft);
}
.chat-row:first-child { animation: uac-fade-in 180ms ease-out both; }

.chat-row__time {
  font-size: 11px;
  color: var(--text-faint);
  padding-top: 3px;
  text-align: right;
}
.chat-row__role {
  font-size: 11px;
  color: var(--text-mute);
  padding-top: 3px;
  font-weight: 500;
}
.chat-row--user .chat-row__role { color: var(--signal-2); }
.chat-row--assistant .chat-row__role { color: var(--signal); }
.chat-row__body { min-width: 0; max-width: 80ch; }

.chat-row__content {
  font-size: 14px;
  line-height: var(--lh-base);
  color: var(--text);
  word-break: break-word;
}
.chat-row__content :deep(p) { margin: 0 0 8px; }
.chat-row__content :deep(p:last-child) { margin-bottom: 0; }
.chat-row__content :deep(pre) {
  background: var(--bg-surface);
  padding: 10px 12px;
  border: 1px solid var(--border);
  font-size: 12px;
  overflow-x: auto;
  margin: 8px 0;
}
.chat-row__content :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.92em;
  background: var(--bg-surface);
  padding: 1px 5px;
  color: var(--text);
}
.chat-row__content :deep(pre code) { background: transparent; padding: 0; }
.chat-row__content :deep(ul),
.chat-row__content :deep(ol) { padding-left: 22px; margin: 4px 0; }
.chat-row__content :deep(table) { border-collapse: collapse; margin: 8px 0; font-size: 13px; }
.chat-row__content :deep(th),
.chat-row__content :deep(td) {
  border: 1px solid var(--border);
  padding: 4px 10px;
  text-align: left;
}
.chat-row__content :deep(th) {
  background: var(--bg-surface);
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--text-mute);
}
.chat-row__actions {
  margin-top: 6px;
}
.chat-row__action {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--text-faint);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
  transition: color 0.1s ease;
}
.chat-row__action:hover { color: var(--signal); }

/* ============ input ============ */
.chat-input {
  border-top: 1px solid var(--border);
  padding: 12px 20px 14px;
  background: var(--bg-surface);
  flex-shrink: 0;
}
.chat-input__textarea {
  width: 100%;
  border: none;
  border-bottom: 1px solid var(--border);
  resize: vertical;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: var(--lh-base);
  color: var(--text);
  padding: 8px 0;
  background: transparent;
  outline: none;
}
.chat-input__textarea:focus { border-bottom-color: var(--signal); }
.chat-input__textarea::placeholder { color: var(--text-faint); }
.chat-input__textarea:disabled { color: var(--text-faint); cursor: not-allowed; }

.chat-input__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.chat-input__meta { font-size: 11px; color: var(--text-faint); }
.chat-input__sep { color: var(--border); font-size: 11px; }
.chat-input__spacer { flex: 1; }
.chat-input__enter {
  font-size: 11px;
  opacity: 0.8;
}

.chat-input__btn { padding: 6px 12px; font-size: 12px; }
</style>
