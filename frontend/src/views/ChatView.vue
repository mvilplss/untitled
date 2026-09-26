<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { marked } from 'marked'
import { useAgentsStore } from '@/stores/agents'
import { useChatStore } from '@/stores/chat'
import ReasoningPanel from '@/components/ReasoningPanel.vue'
import UsageStats from '@/components/UsageStats.vue'
import { fmtTime } from '@/utils/datetime'
import type { ChatMessage } from '@/types/api'

const route = useRoute()
const agentsStore = useAgentsStore()
const chatStore = useChatStore()

const input = ref('')
const scrollEl = ref<HTMLElement>()
const agentPickerOpen = ref(false)
const agentPickerEl = ref<HTMLElement | null>(null)
const sandboxDrawerOpen = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const dragOver = ref(false)

function openSandboxDrawer() {
  if (!chatStore.agentId) return
  sandboxDrawerOpen.value = true
}

onMounted(() => agentsStore.fetchList())

watch(() => agentsStore.list, async (list) => {
  const queryAgent = (route.query.agent as string | undefined)?.trim()
  const querySession = (route.query.session as string | undefined)?.trim()
  if (queryAgent && list.find(a => a.id === queryAgent)) {
    if (chatStore.agentId !== queryAgent) await chatStore.setAgentId(queryAgent)
    if (querySession) {
      chatStore.switchSession(querySession)
    }
    return
  }
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
      (m.toolCalls || []).map(t => (t.name || '') + (t.arguments || '') + (t.output || '')).join('') +
      (m.usages || []).map(u => `${u.seq ?? ''}:${u.totalTokens ?? 0}`).join(''),
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
  const hasAttachments = chatStore.stagedAttachments.length > 0
  if ((!text && !hasAttachments) || !chatStore.canSend) return
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
  chatStore.clearStagedAttachments()
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

function triggerFileSelect() {
  fileInputRef.value?.click()
}

async function handleFileSelect(e: Event) {
  const target = e.target as HTMLInputElement
  const files = Array.from(target.files ?? [])
  if (files.length) {
    try { await chatStore.addFiles(files) } catch { /* error shown in store */ }
  }
  target.value = ''
}

function handleDragOver(e: DragEvent) {
  e.preventDefault()
  dragOver.value = true
}

function handleDragLeave(e: DragEvent) {
  e.preventDefault()
  dragOver.value = false
}

async function handleDrop(e: DragEvent) {
  e.preventDefault()
  dragOver.value = false
  const files = Array.from(e.dataTransfer?.files ?? [])
  if (files.length) {
    try { await chatStore.addFiles(files) } catch { /* error shown in store */ }
  }
}

async function handlePaste(e: ClipboardEvent) {
  const items = Array.from(e.clipboardData?.items ?? [])
  const files: File[] = []
  for (const item of items) {
    if (item.kind === 'file') {
      const f = item.getAsFile()
      if (f) files.push(f)
    }
  }
  if (files.length) {
    e.preventDefault()
    try { await chatStore.addFiles(files) } catch { /* error shown in store */ }
  }
}

function removeStagedAttachment(id: string) {
  chatStore.removeStagedAttachment(id)
}

function fmtFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
  } catch {
    /* ignore */
  }
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
            class="agent-picker__trigger"
            :class="{ 'agent-picker__trigger--open': agentPickerOpen }"
            @click="toggleAgentPicker"
          >
            <span class="agent-picker__dot"></span>
            <span class="agent-picker__name">{{ (selectedAgent?.name && selectedAgent.name.trim()) || selectedAgent?.id || '—' }}</span>
            <span
              v-if="selectedAgent && selectedAgent.name && selectedAgent.name.trim() && selectedAgent.id !== selectedAgent.name"
              class="agent-picker__id-sub mono"
            >{{ selectedAgent.id }}</span>
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
                <span class="agent-picker__item-name">{{ a.name && a.name.trim() ? a.name : a.id }}</span>
                <span class="agent-picker__item-id mono">{{ a.id }}</span>
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
              <span v-if="s.isTask" class="session__badge mono" title="定时任务会话">任务</span>
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
          <span class="chat-meta__val">{{ (selectedAgent?.name && selectedAgent.name.trim()) || '—' }}</span>
          <span
            v-if="chatStore.agentId"
            class="chat-meta__id-hint mono"
            :title="chatStore.agentId"
          >{{ chatStore.agentId }}</span>
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
        <div class="chat-meta__spacer"></div>
        <button
          class="chat-meta__action mono"
          :disabled="!chatStore.agentId"
          title="浏览沙箱容器内文件"
          @click="openSandboxDrawer"
        >
          <span class="chat-meta__action-icon">▤</span>
          文件
        </button>
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
              <!-- 用户消息附件 chips -->
              <div v-if="m.role === 'user' && m.attachments?.length" class="chat-msg-attachments">
                <div
                  v-for="a in m.attachments"
                  :key="a.id"
                  class="chat-attachment-chip chat-attachment-chip--msg"
                >
                  <span class="chat-attachment-chip__icon">{{ a.mime.startsWith('image/') ? '🖼' : '📄' }}</span>
                  <span class="chat-attachment-chip__name mono">{{ a.name }}</span>
                  <span class="chat-attachment-chip__size mono">{{ fmtFileSize(a.size) }}</span>
                </div>
              </div>
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
              <UsageStats
                v-if="m.role === 'assistant' && m.usages && m.usages.length"
                :usages="m.usages"
              />
            </div>
          </div>
        </div>
      </div>

      <div
        class="chat-input"
        :class="{ 'chat-input--dragover': dragOver }"
        @dragover="handleDragOver"
        @dragleave="handleDragLeave"
        @drop="handleDrop"
      >
        <!-- 暂存附件 chips -->
        <div v-if="chatStore.stagedAttachments.length" class="chat-attachments">
          <div
            v-for="a in chatStore.stagedAttachments"
            :key="a.id"
            class="chat-attachment-chip"
          >
            <span class="chat-attachment-chip__icon">{{ a.mime.startsWith('image/') ? '🖼' : '📄' }}</span>
            <span class="chat-attachment-chip__name mono">{{ a.name }}</span>
            <span class="chat-attachment-chip__size mono">{{ fmtFileSize(a.size) }}</span>
            <button class="chat-attachment-chip__remove" @click="removeStagedAttachment(a.id)">×</button>
          </div>
        </div>
        <textarea
          v-model="input"
          class="chat-input__textarea mono"
          rows="3"
          :placeholder="chatStore.agentId ? '// 输入消息 — ↵ 发送（可粘贴 / 拖拽图片）' : '// 请先选择数字人'"
          :disabled="!chatStore.agentId"
          @keydown="handleKeyDown"
          @paste="handlePaste"
        ></textarea>
        <input
          ref="fileInputRef"
          type="file"
          multiple
          accept="image/*,.pdf,.txt,.md,.docx,.xlsx,.csv,.json,.log"
          style="display: none"
          @change="handleFileSelect"
        />
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
          <button
            class="btn-ghost mono chat-input__btn"
            :disabled="!chatStore.agentId"
            @click="triggerFileSelect"
            title="上传图片 / 文件"
          >
            📎
          </button>
          <button class="btn-ghost mono chat-input__btn" @click="handleClear" :disabled="!chatStore.messages.length && !chatStore.stagedAttachments.length">
            清空
          </button>
          <button
            class="btn-primary chat-input__btn"
            :disabled="(!input.trim() && !chatStore.stagedAttachments.length) || !chatStore.agentId || chatStore.loading"
            @click="handleSend"
          >
            发送
            <span class="chat-input__enter mono">↵</span>
          </button>
        </div>
      </div>
    </section>

    <SandboxFileDrawer
      v-model="sandboxDrawerOpen"
      :agent-id="chatStore.agentId"
      :user-id="chatStore.userId"
      :session-id="chatStore.sessionId"
    />
  </div>
</template>

<style scoped>
.chat-view {
  display: grid;
  grid-template-columns: 264px 1fr;
  grid-template-rows: 100%;
  height: calc(100vh - 56px - 60px); /* 56 topbar + 60 bottom pad */
  margin: -22px -26px -60px;          /* offset App.vue main padding */
  background: var(--bg);
  overflow: hidden;
}

/* ============ sidebar ============ */
.chat-sidebar {
  border-right: 1px solid var(--line);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow: hidden;
  min-height: 0;
  background: var(--panel);
}
.chat-sidebar__block {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.chat-sidebar__block--grow { flex: 1; min-height: 0; }
.chat-sidebar__block-head {
  font-size: 11px;
  color: var(--txt2);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--line-soft);
}
.chat-sidebar__count { color: var(--txt3); }
.chat-sidebar__hint {
  font-size: 12px;
  color: var(--txt2);
  line-height: 1.5;
  padding: 4px 0;
}
.chat-sidebar__hint a {
  color: var(--primary);
  text-decoration: underline;
  text-underline-offset: 2px;
}
.chat-sidebar__hint--block {
  padding: 12px 8px;
  text-align: center;
  border: 1px dashed var(--line);
  border-radius: var(--radius-sm);
  font-size: 11px;
  color: var(--txt3);
}

/* ============ agent picker ============ */
.agent-picker { position: relative; }
.agent-picker__trigger {
  width: 100%;
  display: grid;
  grid-template-columns: 10px 1fr auto 14px;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  background: #fafbfc;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  cursor: pointer;
  font-size: 12px;
  color: var(--txt);
  text-align: left;
  transition: border-color 0.12s ease;
}
.agent-picker__trigger:hover { border-color: var(--txt2); }
.agent-picker__trigger--open { border-color: var(--primary); }
.agent-picker__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--green);
  box-shadow: 0 0 0 3px rgba(22,163,74,.15);
  display: inline-block;
}
.agent-picker__name {
  font-size: 13px;
  font-weight: 500;
  color: var(--txt);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.agent-picker__id-sub {
  font-size: 11px;
  color: var(--txt3);
  user-select: all;
  white-space: nowrap;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.agent-picker__caret {
  color: var(--txt2);
  font-size: 11px;
  text-align: right;
}

.agent-picker__menu {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  z-index: var(--z-dropdown);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  box-shadow: var(--shadow);
  max-height: 360px;
  overflow-y: auto;
}
.agent-picker__menu-head {
  padding: 6px 10px;
  border-bottom: 1px solid var(--line-soft);
  font-size: 11px;
  color: var(--txt2);
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
  border-bottom: 1px solid var(--line-soft);
  cursor: pointer;
  font-family: inherit;
  font-size: 12px;
  text-align: left;
  color: var(--txt2);
  transition: background-color 0.08s ease, color 0.08s ease;
}
.agent-picker__item:last-child { border-bottom: none; }
.agent-picker__item:hover { background: var(--bg); color: var(--txt); }
.agent-picker__item--active {
  background: var(--primary-soft);
  color: var(--primary);
  border-left: 2px solid var(--primary);
  padding-left: 8px;
}
.agent-picker__item-dot {
  width: 6px;
  height: 6px;
  background: var(--line);
  display: inline-block;
}
.agent-picker__item--active .agent-picker__item-dot { background: var(--green); }
.agent-picker__item-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}
.agent-picker__item-id {
  font-size: 11px;
  color: var(--txt3);
  user-select: all;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.agent-picker__item-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--txt);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.agent-picker__item--active .agent-picker__item-name { color: var(--primary); }
.agent-picker__item-model {
  font-size: 11px;
  color: var(--primary);
  border: 1px solid var(--primary-soft);
  background: var(--primary-soft);
  padding: 1px 6px;
  border-radius: 6px;
  white-space: nowrap;
}

.chat-sidebar__btn {
  font-family: var(--font-sans);
  font-size: 12px;
  background: transparent;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  padding: 6px 10px;
  text-align: left;
  cursor: pointer;
  color: var(--txt2);
  transition: border-color 0.1s ease, color 0.1s ease;
}
.chat-sidebar__btn:hover:not(:disabled) {
  border-color: var(--txt2);
  color: var(--txt);
}
.chat-sidebar__btn:disabled { opacity: 0.4; cursor: not-allowed; }

.chat-sidebar__sessions {
  flex: 1;
  overflow-y: auto;
  margin-top: 6px;
}

.session {
  padding: 7px 10px;
  border-bottom: 1px solid var(--line-soft);
  cursor: pointer;
  transition: background-color 0.08s ease;
  border-radius: 0;
}
.session:hover { background: var(--bg); }
.session--active {
  background: var(--primary-soft);
  border-left: 2px solid var(--primary);
  padding-left: 8px;
}
.session__row1 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--txt);
  margin-bottom: 3px;
  white-space: nowrap;
  overflow: hidden;
}
.session__sid {
  font-size: 11px;
  color: var(--txt2);
  flex-shrink: 0;
}
.session--active .session__sid { color: var(--primary); }
.session__badge {
  flex-shrink: 0;
  font-size: 10px;
  line-height: 1;
  padding: 2px 5px;
  border-radius: 2px;
  background: var(--primary-soft);
  color: var(--primary);
}
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
  color: var(--txt3);
}
.session__sep { color: var(--line); }
.session__spacer { flex: 1; }
.session__del {
  font-family: var(--font-mono);
  background: transparent;
  border: none;
  color: var(--txt3);
  cursor: pointer;
  padding: 0 4px;
  font-size: 14px;
  line-height: 1;
}
.session__del:hover { color: var(--red); }

/* ============ main column ============ */
.chat-main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: var(--bg);
}

/* ============ meta strip ============ */
.chat-meta {
  display: flex;
  align-items: center;
  gap: 0;
  padding: 8px 20px;
  background: var(--panel);
  border-bottom: 1px solid var(--line);
  font-size: 11px;
  flex-shrink: 0;
}
.chat-meta__cell {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  border-right: 1px solid var(--line-soft);
}
.chat-meta__cell:last-child { border-right: none; }
.chat-meta__cell--active { color: var(--primary); }
.chat-meta__label { color: var(--txt2); }
.chat-meta__val { color: var(--txt); }
.chat-meta__id-hint {
  font-size: 11px;
  color: var(--txt3);
  user-select: all;
  white-space: nowrap;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.chat-meta__cell--active .chat-meta__val { color: var(--primary); }
.chat-meta__pulse {
  width: 6px;
  height: 6px;
  background: var(--primary);
  display: inline-block;
  animation: uac-blink 1s steps(1) infinite;
}
.chat-meta__spacer { flex: 1; }
.chat-meta__action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--txt2);
  background: transparent;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 4px 10px;
  cursor: pointer;
  transition: border-color 0.1s ease, color 0.1s ease;
  margin-right: 14px;
}
.chat-meta__action:hover:not(:disabled) {
  border-color: var(--primary);
  color: var(--primary);
}
.chat-meta__action:disabled { opacity: 0.4; cursor: not-allowed; }
.chat-meta__action-icon { font-size: 12px; line-height: 1; }

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
  color: var(--txt3);
}

.chat-rows { display: flex; flex-direction: column; gap: 0; }

.chat-row {
  display: grid;
  grid-template-columns: 76px 56px 1fr;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--line-soft);
}
.chat-row:first-child { animation: uac-fade-in 180ms ease-out both; }

.chat-row__time {
  font-size: 11px;
  color: var(--txt3);
  padding-top: 3px;
  text-align: right;
}
.chat-row__role {
  font-size: 11px;
  color: var(--txt2);
  padding-top: 3px;
  font-weight: 500;
}
.chat-row--user .chat-row__role { color: var(--primary); }
.chat-row--assistant .chat-row__role { color: var(--primary); }
.chat-row__body { min-width: 0; max-width: 80ch; }

.chat-row__content {
  font-size: 14px;
  line-height: 1.6;
  color: var(--txt);
  word-break: break-word;
}
.chat-row__content :deep(p) { margin: 0 0 8px; }
.chat-row__content :deep(p:last-child) { margin-bottom: 0; }
.chat-row__content :deep(pre) {
  background: var(--panel);
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  font-size: 12px;
  overflow-x: auto;
  margin: 8px 0;
}
.chat-row__content :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.92em;
  background: var(--panel);
  padding: 1px 5px;
  border-radius: 4px;
  color: var(--txt);
}
.chat-row__content :deep(pre code) { background: transparent; padding: 0; }
.chat-row__content :deep(ul),
.chat-row__content :deep(ol) { padding-left: 22px; margin: 4px 0; }
.chat-row__content :deep(table) { border-collapse: collapse; margin: 8px 0; font-size: 13px; }
.chat-row__content :deep(th),
.chat-row__content :deep(td) {
  border: 1px solid var(--line);
  padding: 4px 10px;
  text-align: left;
}
.chat-row__content :deep(th) {
  background: var(--panel);
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--txt2);
}
.chat-row__actions { margin-top: 6px; }
.chat-row__action {
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--txt3);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 0;
  transition: color 0.1s ease;
}
.chat-row__action:hover { color: var(--primary); }

/* ============ input ============ */
.chat-input {
  border-top: 1px solid var(--line);
  padding: 12px 20px 14px;
  background: var(--panel);
  flex-shrink: 0;
}
.chat-input__textarea {
  width: 100%;
  border: none;
  border-bottom: 1px solid var(--line);
  resize: vertical;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
  color: var(--txt);
  padding: 8px 0;
  background: transparent;
  outline: none;
}
.chat-input__textarea:focus { border-bottom-color: var(--primary); }
.chat-input__textarea::placeholder { color: var(--txt3); }
.chat-input__textarea:disabled { color: var(--txt3); cursor: not-allowed; }

.chat-input__bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.chat-input__meta { font-size: 11px; color: var(--txt3); }
.chat-input__sep { color: var(--line); font-size: 11px; }
.chat-input__spacer { flex: 1; }
.chat-input__enter { font-size: 11px; opacity: 0.8; }

.chat-input__btn { padding: 6px 12px; font-size: 12px; }

/* ============ 附件 chips ============ */
.chat-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.chat-attachment-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  font-size: 11px;
  color: var(--txt);
}
.chat-attachment-chip--msg {
  margin-bottom: 6px;
}
.chat-attachment-chip__icon { font-size: 13px; }
.chat-attachment-chip__name {
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.chat-attachment-chip__size { color: var(--txt3); }
.chat-attachment-chip__remove {
  background: none;
  border: none;
  cursor: pointer;
  color: var(--txt3);
  font-size: 14px;
  padding: 0 2px;
  line-height: 1;
}
.chat-attachment-chip__remove:hover { color: var(--red); }

.chat-msg-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}

/* ============ 拖拽高亮 ============ */
.chat-input--dragover {
  border: 2px dashed var(--primary);
  border-radius: var(--radius-sm);
  background: var(--primary-soft);
}
</style>
