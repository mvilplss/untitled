import { http } from './client'
import type { ChatRequest, ChatResponse } from '@/types/api'

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
  onDone: () => void
  onError: (msg: string) => void
}

function buildQueryString(req: ChatRequest): string {
  const params = new URLSearchParams({
    agentId: req.agentId,
    sessionId: req.sessionId ?? 'default',
    userId: req.userId ?? 'anonymous',
    message: req.message,
  })
  return params.toString()
}

export const buildStreamUrl = (req: ChatRequest): string => {
  const qs = buildQueryString(req)
  if (typeof window !== 'undefined' && window.electronAPI) {
    const base = window.electronAPI.getBackendUrl().replace(/\/$/, '')
    return `${base}/api/chat/stream?${qs}`
  }
  return `/api/chat/stream?${qs}`
}

function safeParse(s: string): unknown | null {
  try { return JSON.parse(s) } catch { return null }
}

export const openChatStream = (
  req: ChatRequest,
  handlers: StreamHandlers,
): EventSource => {
  const url = buildStreamUrl(req)
  const es = new EventSource(url)
  let finished = false

  es.addEventListener('message', (ev: MessageEvent) => {
    handlers.onMessage(ev.data)
  })

  es.addEventListener('thinking', (ev: MessageEvent) => {
    handlers.onThinking(ev.data)
  })

  es.addEventListener('tool_start', (ev: MessageEvent) => {
    const p = safeParse(ev.data) as { id?: string; name?: string } | null
    if (p && p.id) handlers.onToolStart({ id: p.id, name: p.name ?? '' })
  })

  es.addEventListener('tool_delta', (ev: MessageEvent) => {
    const p = safeParse(ev.data) as { id?: string; delta?: string } | null
    if (p && p.id) handlers.onToolDelta({ id: p.id, delta: p.delta ?? '' })
  })

  es.addEventListener('tool_end', (ev: MessageEvent) => {
    const p = safeParse(ev.data) as { id?: string } | null
    if (p && p.id) handlers.onToolEnd({ id: p.id })
  })

  es.addEventListener('tool_result', (ev: MessageEvent) => {
    const p = safeParse(ev.data) as {
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
  })

  es.addEventListener('done', () => {
    finished = true
    handlers.onDone()
    es.close()
  })

  es.onerror = () => {
    if (!finished) {
      handlers.onError('SSE 连接异常')
    }
    es.close()
  }

  return es
}