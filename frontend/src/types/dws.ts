/**
 * 钉钉 DWS (dingtalk-workspace-cli) 前端类型定义。
 * 与后端 org.example.dws.web.dto 保持一一对应。
 */

export type DwsAuthPhase =
  | 'idle'
  | 'waiting'
  | 'approved'
  | 'expired'
  | 'rejected'
  | 'failed'

export interface DeviceFlowRequest {
  displayName: string
}

export interface DeviceFlowResponse {
  userId: string
  flowId?: string
  userCode: string
  verificationUriComplete: string
  intervalMs: number
  expiresIn: number
  expiresAt: number
}

export interface DwsProfileDto {
  userId: string
  displayName?: string
  corpId: string
  corpName: string
  dwsUserId: string
  userName: string
  status: string
  lastLoginAt?: number | null
}

export interface DwsRegistryEntry {
  userId?: string
  corpId?: string
  corpName?: string
  displayName?: string
  lastLoginAt?: number | null
  status?: string
}

export interface DwsRegistryResponse {
  current: DwsRegistryEntry | null
  users: Record<string, DwsRegistryEntry>
}

export interface DwsSwitchRequest {
  selector: string
}

export interface DwsStatusResponse {
  userId: string
  phase: DwsAuthPhase
  registry?: DwsRegistryEntry | null
}

export interface DwsAuditResponse {
  limit: number
  file: string
  content: string
}

/** SSE 事件载荷 */
export interface DwsFlowStateEvent {
  userId?: string
  phase?: DwsAuthPhase | string
  userCode?: string
  verificationUriComplete?: string
  expiresAt?: number
  intervalMs?: number
  profile?: {
    corpId: string
    corpName: string
    userId: string
  }
  reason?: string
}
