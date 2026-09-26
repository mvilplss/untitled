import { http } from './client'
import { inElectron } from '@/utils/env'
import type {
  DeviceFlowRequest,
  DeviceFlowResponse,
  DwsAuditResponse,
  DwsProfileDto,
  DwsRegistryResponse,
  DwsStatusResponse,
  DwsSwitchRequest,
  DwsFlowStateEvent,
} from '@/types/dws'

/** 发起 device-flow。后端返回 userCode + URL；后续通过 SSE 订阅进度。 */
export const startDeviceFlow = (req: DeviceFlowRequest): Promise<DeviceFlowResponse> =>
  http.post<DeviceFlowResponse>('/dws/auth/device', req).then(r => r.data)

/** 查询当前授权 phase（同步；SSE 失败时降级用） */
export const getAuthStatus = (userId: string): Promise<DwsStatusResponse> =>
  http.get<DwsStatusResponse>('/dws/auth/status', { params: { userId } }).then(r => r.data)

/** 清空指定 userId 所有授权 */
export const resetAuth = (userId: string): Promise<void> =>
  http.post<void>('/dws/auth/reset', null, { params: { userId } }).then(() => undefined)

/** 列出当前 userId 下的所有 profile */
export const listProfiles = (userId: string): Promise<DwsProfileDto[]> =>
  http.get<DwsProfileDto[]>('/dws/profile/list', { params: { userId } }).then(r => r.data)

/** 切换默认 profile */
export const switchProfile = (userId: string, selector: string): Promise<void> =>
  http.post<void>('/dws/profile/switch', { selector } as DwsSwitchRequest).then(() => undefined)

/** 删除单个 profile */
export const removeProfile = (userId: string, corpId: string, dwsUserId: string): Promise<void> =>
  http.delete<void>(`/dws/profile/${encodeURIComponent(corpId)}:${encodeURIComponent(dwsUserId)}`)
    .then(() => undefined)

/** 查询本地注册表 */
export const getRegistry = (userId: string): Promise<DwsRegistryResponse> =>
  http.get<DwsRegistryResponse>('/dws/profile/registry').then(r => r.data)

/** 查询审计日志 */
export const getAudit = (limit = 50): Promise<DwsAuditResponse> =>
  http.get<DwsAuditResponse>('/dws/audit', { params: { limit } }).then(r => r.data)

/**
 * 打开 SSE 订阅 device-flow 进度。复用现有 axios + EventSource 模式。
 * X-User-Id 由 client.ts 拦截器统一注入。
 */
function buildStreamUrl(userId: string): string {
  if (inElectron && window.electronAPI) {
    const base = window.electronAPI.getBackendUrl().replace(/\/$/, '')
    return `${base}/api/dws/auth/stream/${encodeURIComponent(userId)}`
  }
  return `/api/dws/auth/stream/${encodeURIComponent(userId)}`
}

export interface DwsAuthStreamHandlers {
  onState: (state: DwsFlowStateEvent) => void
  onDone: (state: DwsFlowStateEvent) => void
  onError: (msg: string) => void
}

export const openAuthStream = (userId: string, handlers: DwsAuthStreamHandlers): EventSource => {
  const url = buildStreamUrl(userId)
  const es = new EventSource(url)

  es.addEventListener('flow_state', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data) as DwsFlowStateEvent
      handlers.onState(data)
    } catch {
      handlers.onState({})
    }
  })

  es.addEventListener('done', (ev: MessageEvent) => {
    try {
      const data = JSON.parse(ev.data) as DwsFlowStateEvent
      handlers.onDone(data)
    } catch {
      handlers.onDone({})
    }
    es.close()
  })

  es.onerror = () => {
    handlers.onError('SSE 连接异常，请检查后端是否启动')
    es.close()
  }

  return es
}
