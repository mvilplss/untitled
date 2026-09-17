import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as chatApi from '@/api/chat'
import * as sessionsApi from '@/api/sessions'
import type { ChatHistoryMessage, ChatMessage, ChatRequest, SessionInfo, ToolCall } from '@/types/api'

let _id = 0
const newId = () => `m_${Date.now()}_${++_id}`

export type ChatMode = 'sync' | 'stream'

function generateSessionId(): string {
  return `s-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`
}

/** 把后端 ChatHistoryMessage 转前端 ChatMessage */
function historyToMessages(history: ChatHistoryMessage[]): ChatMessage[] {
  const out: ChatMessage[] = []
  for (const h of history) {
    if (!h.content && !h.thinking && !h.toolCalls?.length) continue
    const role = h.role === 'USER' ? 'user' as const : 'assistant' as const
    out.push({
      id: `hist_${h.timestamp}_${out.length}`,
      role,
      content: h.content || '',
      thinking: h.thinking || undefined,
      toolCalls: h.toolCalls,
      createdAt: h.timestamp * 1000,
    })
  }
  return out
}

/** 相对时间格式化 */
function formatRelative(ts: number): string {
  if (!ts) return '从未'
  const sec = Math.floor((Date.now() / 1000 - ts))
  if (sec < 60) return `${sec}秒前`
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}分钟前`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr}小时前`
  const day = Math.floor(hr / 24)
  if (day < 30) return `${day}天前`
  return new Date(ts * 1000).toLocaleDateString()
}

/** 一次性清理老 localStorage key（chat:*），避免残留干扰新逻辑 */
function migrateClearLegacyLocalStorage() {
  try {
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i)
      if (k && k.startsWith('chat:')) localStorage.removeItem(k)
    }
  } catch {
    // 忽略
  }
}

/** 在 ChatMessage 上把指定 id 的工具调用取出（不存在则新建） */
function ensureToolCall(msg: ChatMessage, id: string, name: string): ToolCall {
  if (!msg.toolCalls) msg.toolCalls = []
  let tc = msg.toolCalls.find(t => t.id === id)
  if (!tc) {
    tc = { id, name, arguments: '', output: '', state: 'RUNNING' }
    msg.toolCalls.push(tc)
  } else if (name && !tc.name) {
    tc.name = name
  }
  return tc
}

export const useChatStore = defineStore('chat', () => {
  // 一次性迁移：清理老 chat:* localStorage
  migrateClearLegacyLocalStorage()

  // 全部为纯内存状态（不持久化）
  const agentId = ref<string>('')
  const userId = ref<string>('anonymous')
  const mode = ref<ChatMode>('stream')

  /** 空字符串 = 未开始对话。sessionId 只在首次 send() 时生成。 */
  const sessionId = ref<string>('')

  const messages = ref<ChatMessage[]>([])
  const loading = ref(false)
  const streaming = ref(false)

  const sessions = ref<SessionInfo[]>([])
  const loadingSessions = ref(false)
  const loadingHistory = ref(false)

  let currentES: EventSource | null = null
  let currentAssistantId: string | null = null

  const canSend = computed(() => !!agentId.value && !loading.value)

  function cancelStream() {
    if (currentES) {
      currentES.close()
      currentES = null
    }
    if (currentAssistantId) {
      const msg = messages.value.find(m => m.id === currentAssistantId)
      if (msg) {
        msg.streaming = false
        msg.toolCallsStreaming = false
      }
      currentAssistantId = null
    }
    streaming.value = false
  }

  /**
   * 发送消息。
   * 关键：sessionId 为空时（即尚未开始任何对话）才生成新的；发送后由 onDone 触发 loadSessions。
   */
  async function send(text: string) {
    if (!agentId.value || !text.trim()) return

    // 懒创建 sessionId
    if (!sessionId.value) {
      sessionId.value = generateSessionId()
    }

    const req: ChatRequest = {
      agentId: agentId.value,
      sessionId: sessionId.value,
      userId: userId.value,
      message: text,
    }

    const wasFirstInSession = messages.value.length === 0

    messages.value.push({
      id: newId(),
      role: 'user',
      content: text,
      createdAt: Date.now(),
    })

    if (mode.value === 'sync') {
      loading.value = true
      try {
        const resp = await chatApi.sendChat(req)
        messages.value.push({
          id: newId(),
          role: 'assistant',
          content: resp.reply,
          thinking: resp.thinking,
          toolCalls: resp.toolCalls,
          createdAt: Date.now(),
        })
        if (wasFirstInSession) await loadSessions(agentId.value)
      } finally {
        loading.value = false
      }
      return
    }

    const assistantId = newId()
    currentAssistantId = assistantId
    messages.value.push({
      id: assistantId,
      role: 'assistant',
      content: '',
      thinking: '',
      toolCalls: [],
      streaming: true,
      toolCallsStreaming: true,
      createdAt: Date.now(),
    })
    streaming.value = true

    currentES = await chatApi.openChatStream(req, {
      onMessage: (chunk) => {
        const msg = messages.value.find(m => m.id === assistantId)
        if (msg) msg.content += chunk
      },
      onThinking: (chunk) => {
        const msg = messages.value.find(m => m.id === assistantId)
        if (msg) msg.thinking = (msg.thinking || '') + chunk
      },
      onToolStart: ({ id, name }) => {
        const msg = messages.value.find(m => m.id === assistantId)
        if (!msg) return
        ensureToolCall(msg, id, name)
      },
      onToolDelta: ({ id, delta }) => {
        const msg = messages.value.find(m => m.id === assistantId)
        if (!msg) return
        const tc = ensureToolCall(msg, id, '')
        tc.arguments = (tc.arguments || '') + delta
      },
      onToolEnd: ({ id }) => {
        const msg = messages.value.find(m => m.id === assistantId)
        if (!msg) return
        const tc = ensureToolCall(msg, id, '')
        // arguments 收尾：尝试 trim 一下 JSON 残留的空格
        if (tc.arguments) tc.arguments = tc.arguments.trim()
      },
      onToolResult: ({ id, name, state, output }) => {
        const msg = messages.value.find(m => m.id === assistantId)
        if (!msg) return
        const tc = ensureToolCall(msg, id, name)
        tc.state = state as ToolCall['state']
        tc.output = output
      },
      onDone: async () => {
        const msg = messages.value.find(m => m.id === assistantId)
        if (msg) {
          msg.streaming = false
          msg.toolCallsStreaming = false
        }
        currentAssistantId = null
        currentES = null
        streaming.value = false
        if (wasFirstInSession) await loadSessions(agentId.value)
      },
      onError: (msg) => {
        const m = messages.value.find(m => m.id === assistantId)
        if (m) {
          m.content += (m.content ? '\n\n' : '') + `**[错误]** ${msg}`
          m.streaming = false
          m.toolCallsStreaming = false
        }
        currentAssistantId = null
        currentES = null
        streaming.value = false
      },
    })
  }

  /** 切换 agent：清空当前会话 + 拉新 agent 的历史列表 */
  async function setAgentId(id: string, opts?: { force?: boolean }) {
    if (id === agentId.value && !opts?.force) return
    cancelStream()
    agentId.value = id
    sessionId.value = ''
    messages.value = []
    await loadSessions(id)
  }

  /** 「新对话」按钮：清空当前会话，不调后端（会话真的开始时由 send 触发） */
  async function newConversation() {
    if (!agentId.value) return
    cancelStream()
    sessionId.value = ''
    messages.value = []
    // sessions 列表不动（后端没有"新空会话"要创建）
  }

  /** 切换到历史会话：从后端拉消息历史 */
  async function switchSession(sid: string) {
    if (!agentId.value || sid === sessionId.value) return
    cancelStream()
    sessionId.value = sid
    loadingHistory.value = true
    try {
      const history = await sessionsApi.getSessionMessages(agentId.value, sid)
      messages.value = historyToMessages(history)
    } finally {
      loadingHistory.value = false
    }
  }

  /** 删除会话：后端 + 从列表移除，若删的是当前则跳转或清空 */
  async function deleteSession(sid: string) {
    if (!agentId.value) return
    const isCurrent = sid === sessionId.value
    try {
      await sessionsApi.deleteSession(agentId.value, sid)
    } catch {
      // 后端 404 等情况，静默继续清理
    }
    sessions.value = sessions.value.filter(s => s.sessionId !== sid)

    if (isCurrent) {
      cancelStream()
      sessionId.value = ''
      messages.value = []

      if (sessions.value.length > 0) {
        await switchSession(sessions.value[0].sessionId)
      }
    }
  }

  /** 拉后端 session 列表（不再合并本地） */
  async function loadSessions(aid: string) {
    if (!aid) {
      sessions.value = []
      return
    }
    loadingSessions.value = true
    try {
      sessions.value = await sessionsApi.listSessions(aid).catch(() => [] as SessionInfo[])
    } finally {
      loadingSessions.value = false
    }
  }

  return {
    agentId, sessionId, userId, mode,
    messages, loading, streaming,
    sessions, loadingSessions, loadingHistory,
    canSend,
    send, cancelStream,
    setAgentId, newConversation, switchSession, deleteSession,
    loadSessions, formatRelative,
  }
})