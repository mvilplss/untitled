export interface DingTalkBotConfig {
  enabled: boolean
  appKey?: string
  /** 后端永远返回 '***' 掩码；上传时若传 '***' 视作沿用旧值 */
  appSecret?: string
  robotCode?: string
}

export interface BailianRagConfig {
  enabled: boolean
  accessKeyId?: string
  /** 后端永远返回 '***' 掩码；上传时若传 '***' 视作沿用旧值 */
  accessKeySecret?: string
  workspaceId?: string
  indexId?: string
  endpoint?: string
  limit?: number
  scoreThreshold?: number
  enableRerank?: boolean
  rerankModel?: string
  rerankMinScore?: number
  rerankTopN?: number
  enableRewrite?: boolean
  rewriteModel?: string
}

export type AgentStatus = 'online' | 'maintain' | 'offline'
export type ToolType = 'MCP' | 'HTTP' | 'DB' | 'RPA'
export type ToolPermission = 'read' | 'write'
export type ToolSideEffect = 'read' | 'write' | 'irreversible'

/** 沙箱隔离 scope：USER=每用户独立容器；AGENT=同 Agent 共享；GLOBAL=全局共享。创建后不可修改。 */
export type IsolationScope = 'USER' | 'AGENT' | 'GLOBAL'

export interface AgentSpec {
  id: string
  name: string
  sysPrompt: string
  modelName: string
  tools?: string[]
  skills?: string[]
  dingtalk?: DingTalkBotConfig
  bailian?: BailianRagConfig
  /** 沙箱隔离 scope。创建后不可修改。默认 USER。 */
  isolationScope?: IsolationScope
  /** optional; not returned by backend today */
  dept?: string
  owner?: string
  version?: string
  status?: AgentStatus
}

export type AgentMode = 'create' | 'edit'

export interface ChatRequest {
  agentId: string
  sessionId?: string
  userId?: string
  message: string
  attachments?: AttachmentRef[]
}

export interface AttachmentRef {
  id: string
  name: string
  path: string
  containerPath?: string
  size: number
  mime: string
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

/** 单次 LLM 调用的 token / 耗时统计。AgentScope ChatUsage 经后端聚合后产出。 */
export interface UsageStats {
  /** 同次会话内的调用序号（0 开始） */
  seq?: number
  /** 本次 LLM 调用耗时（秒） */
  time: number
  /** 输入 token 数（prompt_tokens） */
  inputTokens: number
  /** 输出 token 数（completion_tokens，含 reasoning） */
  outputTokens: number
  /** 命中缓存的输入 token 数（prompt_tokens_details.cached_tokens） */
  cachedTokens: number
  /** 总 token 数（input + output） */
  totalTokens: number
  /** AgentScope 内部 replyId，用于排查 */
  replyId?: string
  /** 是否产生了带文本 / 思考的 assistant 消息条目（仅历史匹配内部使用，前端展示可不关心） */
  createsEntry?: boolean
}

export interface ChatResponse {
  reply: string
  thinking?: string
  toolCalls?: ToolCall[]
  /** 本次回复的 LLM 调用统计列表（按调用顺序） */
  usages?: UsageStats[]
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
  /** 本条 assistant 消息对应的 LLM 调用统计列表（流式中逐次追加） */
  usages?: UsageStats[]
  /** 本条 user 消息关联的附件列表 */
  attachments?: AttachmentRef[]
  createdAt: number
}

export interface SessionInfo {
  sessionId: string
  messageCount: number
  lastActive: number
  preview?: string
  /** 是否为定时任务会话（sessionId 以 task- 开头）。preview 显示任务名而非 prompt 前缀。 */
  isTask?: boolean
}

export interface ChatHistoryMessage {
  role: 'USER' | 'ASSISTANT'
  content: string
  thinking?: string
  toolCalls?: ToolCall[]
  /** 该 assistant 消息关联的 LLM 调用统计列表（侧载自 usage sidecar；空表示无数据） */
  usages?: UsageStats[]
  /** 该 user 消息关联的附件列表 */
  attachments?: AttachmentRef[]
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

/** 沙箱目录列表条目 */
export interface SandboxFileEntry {
  name: string
  /** 容器内绝对路径（/ 开头） */
  path: string
  isDirectory: boolean
  size: number
  /** ISO-8601 时间串，空串表示后端未提供 */
  modifiedAt: string
}

/** 沙箱目录列表响应 */
export interface SandboxFileListResponse {
  path: string
  entries: SandboxFileEntry[]
}

/** 沙箱文件内容响应（content 端点） */
export interface SandboxFileContentResponse {
  path: string
  /** utf-8（文本） 或 base64（二进制） */
  encoding: 'utf-8' | 'base64' | string
  content: string
  size: number
  /** 文本模式下读到 limit 行截断 */
  truncated: boolean
  /** 文件超过预览阈值，前端应直接走 raw 下载 */
  tooLarge: boolean
}

/** 沙箱运行状态（status 端点） */
export interface SandboxStatusResponse {
  running: boolean
  workspaceRoot: string
  containerId: string | null
  agentId: string
}

/** 定时任务定义 */
export interface ScheduledTask {
  id: string
  name: string
  agentId: string
  prompt: string
  skills?: string[]
  cron: string
  enabled: boolean
  createdAt: number
  updatedAt: number
  /** 仅查询响应携带：来自 task_run 聚合 */
  lastRunAt?: number | null
  /** 仅查询响应携带：来自 Quartz Trigger.getNextFireTime()，停用时为 null */
  nextRunAt?: number | null
  /** 仅查询响应携带：便于直接显示数字人显示名 */
  agentName?: string | null
}

/** 任务执行记录 */
export interface TaskRun {
  runId: string
  taskId: string
  triggerType: 'SCHEDULED' | 'MANUAL' | string
  status: 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT' | 'SKIPPED' | string
  startedAt?: number | null
  finishedAt?: number | null
  durationMs?: number | null
  sessionId?: string | null
  replyPreview?: string | null
  error?: string | null
  tokensInput?: number | null
  tokensOutput?: number | null
  tokensCached?: number | null
  tokensTotal?: number | null
}