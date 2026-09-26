import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as chatApi from '@/api/chat'
import * as sessionsApi from '@/api/sessions'
import { formatRelative } from '@/utils/format'
import type { AttachmentRef, ChatHistoryMessage, ChatMessage, ChatRequest, SessionInfo, ToolCall, UsageStats } from '@/types/api'

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
    if (!h.content && !h.thinking && !h.toolCalls?.length && !h.attachments?.length) continue
    const role = h.role === 'USER' ? 'user' as const : 'assistant' as const
    out.push({
      id: `hist_${h.timestamp}_${out.length}`,
      role,
      content: h.content || '',
      thinking: h.thinking || undefined,
      toolCalls: h.toolCalls,
      usages: h.usages,
      attachments: h.attachments,
      createdAt: h.timestamp * 1000,
    })
  }
  return out
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
  // 全部为纯内存状态（不持久化）
  const agentId = ref<string>('')
  const userId = ref<string>('anonymous')
  const mode = ref<ChatMode>('stream')

  /** 空字符串 = 未开始对话。sessionId 只在首次 send() 时生成。 */
  const sessionId = ref<string>('')

  const messages = ref<ChatMessage[]>([])
  const loading = ref(false)
  const streaming = ref(false)

  /** 暂存的待发送附件（上传后等待消息发送） */
  const stagedAttachments = ref<AttachmentRef[]>([])

  const sessions = ref<SessionInfo[]>([])
  const loadingSessions = ref(false)
  const loadingHistory = ref(false)

  let currentController: AbortController | null = null
  let currentAssistantId: string | null = null

  const canSend = computed(() => !!agentId.value && !loading.value)

  function cancelStream() {
    if (currentController) {
      currentController.abort()
      currentController = null
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

    const attachments = [...stagedAttachments.value]
    stagedAttachments.value = []

    const req: ChatRequest = {
      agentId: agentId.value,
      sessionId: sessionId.value,
      userId: userId.value,
      message: text,
      attachments: attachments.length ? attachments : undefined,
    }

    const wasFirstInSession = messages.value.length === 0

    messages.value.push({
      id: newId(),
      role: 'user',
      content: text,
      attachments: attachments.length ? attachments : undefined,
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
          usages: resp.usages,
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

    currentController = chatApi.openChatStream(req, {
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
        // 工具输出已通过 onToolResultDelta 增量累积，仅当尚未收到 delta 时用完整 output 兜底
        if (!tc.output) tc.output = output
      },
      onToolResultDelta: ({ id, name, delta }) => {
        const msg = messages.value.find(m => m.id === assistantId)
        if (!msg) return
        const tc = ensureToolCall(msg, id, name ?? '')
        tc.output = (tc.output || '') + delta
      },
      onUsage: (usage: UsageStats) => {
        const msg = messages.value.find(m => m.id === assistantId)
        if (!msg) return
        if (!msg.usages) msg.usages = []
        msg.usages.push(usage)
      },
      onDone: async () => {
        const msg = messages.value.find(m => m.id === assistantId)
        if (msg) {
          msg.streaming = false
          msg.toolCallsStreaming = false
        }
        currentAssistantId = null
        currentController = null
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
        currentController = null
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
    clearStagedAttachments()
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

  // ==================== 附件管理 ====================

  /** 上传文件到沙箱 uploads 目录，返回 AttachmentRef 并暂存 */
  async function addFiles(files: File[]): Promise<void> {
    if (!agentId.value || !files.length) return
    if (!sessionId.value) sessionId.value = generateSessionId()
    try {
      const refs = await chatApi.uploadChatAttachments(
        agentId.value, userId.value, sessionId.value, files)
      stagedAttachments.value.push(...refs)
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e)
      console.error('附件上传失败:', msg)
      throw e
    }
  }

  /** 移除暂存附件 */
  function removeStagedAttachment(id: string): void {
    stagedAttachments.value = stagedAttachments.value.filter(a => a.id !== id)
  }

  /** 清空暂存附件 */
  function clearStagedAttachments(): void {
    stagedAttachments.value = []
  }

  return {
    agentId, sessionId, userId, mode,
    messages, loading, streaming,
    stagedAttachments,
    sessions, loadingSessions, loadingHistory,
    canSend,
    send, cancelStream,
    addFiles, removeStagedAttachment, clearStagedAttachments,
    setAgentId, newConversation, switchSession, deleteSession,
    loadSessions, formatRelative,
  }
})