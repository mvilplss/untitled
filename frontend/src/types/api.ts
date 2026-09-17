export interface DingTalkBotConfig {
  enabled: boolean
  appKey?: string
  /** 后端永远返回 '***' 掩码；上传时若传 '***' 视作沿用旧值 */
  appSecret?: string
  robotCode?: string
}

export interface AgentSpec {
  id: string
  name: string
  sysPrompt: string
  modelName: string
  tools?: string[]
  skills?: string[]
  dingtalk?: DingTalkBotConfig
}

export type AgentMode = 'create' | 'edit'

export interface ChatRequest {
  agentId: string
  sessionId?: string
  userId?: string
  message: string
}

export interface ToolCall {
  /** 工具调用唯一 ID（同次回复内稳定） */
  id: string
  /** 工具名称 */
  name: string
  /** 模型生成的入参 JSON 字符串（流式中可增量） */
  arguments?: string
  /** 工具执行输出（文本片段拼接后），可能为空 */
  output?: string
  /** 执行结果状态 */
  state?: 'SUCCESS' | 'ERROR' | 'INTERRUPTED' | 'DENIED' | 'RUNNING'
}

export interface ChatResponse {
  reply: string
  thinking?: string
  toolCalls?: ToolCall[]
}

export interface ApiError {
  status: number
  error: string
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  thinking?: string
  toolCalls?: ToolCall[]
  /** 单条工具调用是否还在流式累积（用于打 streaming 角标） */
  toolCallsStreaming?: boolean
  streaming?: boolean
  createdAt: number
}

export interface SessionInfo {
  sessionId: string
  messageCount: number
  lastActive: number
  preview?: string
}

export interface ChatHistoryMessage {
  role: 'USER' | 'ASSISTANT'
  content: string
  thinking?: string
  toolCalls?: ToolCall[]
  timestamp: number
}

export interface SkillInfo {
  name: string
  description: string
  content?: string
  extraMetadata?: Record<string, unknown>
  sizeBytes: number
  modifiedAt: number
  resourceCount: number
}