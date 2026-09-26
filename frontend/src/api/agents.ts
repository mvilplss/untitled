import { http } from './client'
import type { AgentSpec, BailianRagConfig, DingTalkBotConfig } from '@/types/api'

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

export const getBailianRag = (id: string): Promise<BailianRagConfig> =>
  http.get<BailianRagConfig>(`/agents/${encodeURIComponent(id)}/bailian`).then(r => r.data)

export const upsertBailianRag = (id: string, cfg: BailianRagConfig): Promise<BailianRagConfig> =>
  http.put<BailianRagConfig>(`/agents/${encodeURIComponent(id)}/bailian`, cfg).then(r => r.data)

export const deleteBailianRag = (id: string): Promise<void> =>
  http.delete<void>(`/agents/${encodeURIComponent(id)}/bailian`).then(() => undefined)

export interface DingTalkBotHealth {
  agentId: string
  enabled: boolean
  status: 'DISABLED' | 'STARTING' | 'ACTIVE' | 'ERROR'
  lastError?: string
}

export const getDingTalkBotHealth = (id: string): Promise<DingTalkBotHealth> =>
  http.get<DingTalkBotHealth>(`/agents/${encodeURIComponent(id)}/dingtalk/health`).then(r => r.data)