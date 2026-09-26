import { http } from './client'
import { inElectron } from '@/utils/env'
import { parseSSEStream } from './sse'
import type { AttachmentRef, ChatRequest, ChatResponse, UsageStats } from '@/types/api'

export const sendChat = (req: ChatRequest): Promise<ChatResponse> =>
  http.post<ChatResponse>('/chat', req).then(r => r.data)

export interface StreamHandlers {
  onMessage: (chunk: string) => void
  onThinking: (chunk: string) => void
  onToolStart: (payload: { id: string; name: string }) => void
  onToolDelta: (payload: { id: string; delta: string }) => void
  onToolEnd: (payload: { id: string }) => void
  onToolResult: (payload: {
    id: string
    name: string
    state: string
    output: string
  }) => void
  /** 工具执行输出增量 */
  onToolResultDelta: (payload: {
    id: string
    name?: string
    delta: string
  }) => void
  /** 单次 LLM 调用结束：token / 耗时统计 */
  onUsage: (payload: UsageStats) => void
  onDone: () => void
  onError: (msg: string) => void
}

function buildUrl(path: string): string {
  if (inElectron && window.electronAPI) {
    const base = window.electronAPI.getBackendUrl().replace(/\/$/, '')
    return `${base}/api${path}`
  }
  return `/api${path}`
}

function safeParse(s: string): unknown | null {
  try { return JSON.parse(s) } catch { return null }
}

/**
 * 上传聊天附件到沙箱 uploads 目录。
 * POST /api/agents/{id}/chat/upload (multipart)
 */
export async function uploadChatAttachments(
  agentId: string,
  userId: string,
  sessionId: string,
  files: File[],
): Promise<AttachmentRef[]> {
  const formData = new FormData()
  for (const f of files) formData.append('files', f)
  const url = buildUrl(`/agents/${encodeURIComponent(agentId)}/chat/upload?userId=${encodeURIComponent(userId)}&sessionId=${encodeURIComponent(sessionId)}`)
  const resp = await fetch(url, { method: 'POST', body: formData })
  if (!resp.ok) {
    const text = await resp.text().catch(() => '')
    throw new Error(`上传失败 [${resp.status}]: ${text || resp.statusText}`)
  }
  const json = await resp.json()
  return (json.attachments ?? []) as AttachmentRef[]
}

/**
 * 打开 SSE 流式对话（POST JSON body → text/event-stream）。
 * 返回 AbortController 用于取消。
 */
export const openChatStream = (
  req: ChatRequest,
  handlers: StreamHandlers,
): AbortController => {
  const controller = new AbortController()
  const url = buildUrl('/chat/stream')

  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
    signal: controller.signal,
  })
    .then(async resp => {
      if (!resp.ok) {
        const text = await resp.text().catch(() => '')
        handlers.onError(`[${resp.status}] ${text || resp.statusText}`)
        return
      }
      let done = false
      await parseSSEStream(resp, {
        message: (data) => handlers.onMessage(data),
        thinking: (data) => handlers.onThinking(data),
        tool_start: (data) => {
          const p = safeParse(data) as { id?: string; name?: string } | null
          if (p && p.id) handlers.onToolStart({ id: p.id, name: p.name ?? '' })
        },
        tool_delta: (data) => {
          const p = safeParse(data) as { id?: string; delta?: string } | null
          if (p && p.id) handlers.onToolDelta({ id: p.id, delta: p.delta ?? '' })
        },
        tool_end: (data) => {
          const p = safeParse(data) as { id?: string } | null
          if (p && p.id) handlers.onToolEnd({ id: p.id })
        },
        tool_result: (data) => {
          const p = safeParse(data) as {
            id?: string; name?: string; state?: string; output?: string
          } | null
          if (p && p.id) {
            handlers.onToolResult({
              id: p.id,
              name: p.name ?? '',
              state: p.state ?? 'SUCCESS',
              output: p.output ?? '',
            })
          }
        },
        tool_result_delta: (data) => {
          const p = safeParse(data) as {
            id?: string; name?: string; delta?: string
          } | null
          if (p && p.id) {
            handlers.onToolResultDelta({
              id: p.id,
              name: p.name ?? '',
              delta: p.delta ?? '',
            })
          }
        },
        usage: (data) => {
          const p = safeParse(data) as UsageStats | null
          if (p && typeof p.inputTokens === 'number') handlers.onUsage(p)
        },
        done: () => {
          done = true
          handlers.onDone()
        },
        onError: (msg) => {
          if (!done) handlers.onError(msg)
        },
      }, controller.signal)
    })
    .catch((e: unknown) => {
      if (!controller.signal.aborted) {
        const msg = e instanceof Error ? e.message : String(e)
        handlers.onError(msg)
      }
    })

  return controller
}
