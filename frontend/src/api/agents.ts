import { http } from './client'
import type { AgentSpec, DingTalkBotConfig } from '@/types/api'

export const listAgents = (): Promise<AgentSpec[]> => http.get<AgentSpec[]>('/agents').then(r => r.data)

export const getAgent = (id: string): Promise<AgentSpec> =>
  http.get<AgentSpec>(`/agents/${encodeURIComponent(id)}`).then(r => r.data)

export const createAgent = (spec: AgentSpec): Promise<AgentSpec> =>
  http.post<AgentSpec>('/agents', spec).then(r => r.data)

export const updateAgent = (id: string, spec: AgentSpec): Promise<AgentSpec> =>
  http.put<AgentSpec>(`/agents/${encodeURIComponent(id)}`, spec).then(r => r.data)

export const deleteAgent = (id: string): Promise<void> =>
  http.delete<void>(`/agents/${encodeURIComponent(id)}`).then(() => undefined)

export const getDingTalkBot = (id: string): Promise<DingTalkBotConfig> =>
  http.get<DingTalkBotConfig>(`/agents/${encodeURIComponent(id)}/dingtalk`).then(r => r.data)

export const upsertDingTalkBot = (id: string, cfg: DingTalkBotConfig): Promise<DingTalkBotConfig> =>
  http.put<DingTalkBotConfig>(`/agents/${encodeURIComponent(id)}/dingtalk`, cfg).then(r => r.data)

export const deleteDingTalkBot = (id: string): Promise<void> =>
  http.delete<void>(`/agents/${encodeURIComponent(id)}/dingtalk`).then(() => undefined)