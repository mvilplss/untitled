import { http } from './client'
import type { ChatHistoryMessage, SessionInfo } from '@/types/api'

export const listSessions = (agentId: string): Promise<SessionInfo[]> =>
  http
    .get<SessionInfo[]>(`/agents/${encodeURIComponent(agentId)}/sessions`)
    .then(r => r.data)

export const getSessionMessages = (
  agentId: string,
  sessionId: string,
): Promise<ChatHistoryMessage[]> =>
  http
    .get<ChatHistoryMessage[]>(
      `/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionId)}/messages`,
    )
    .then(r => r.data)

export const deleteSession = (
  agentId: string,
  sessionId: string,
): Promise<void> =>
  http
    .delete<void>(
      `/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionId)}`,
    )
    .then(() => undefined)