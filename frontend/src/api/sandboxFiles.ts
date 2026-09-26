import { http } from './client'
import type {
  SandboxFileContentResponse,
  SandboxFileListResponse,
  SandboxStatusResponse,
} from '@/types/api'

/** 沙箱文件浏览 API 客户端（只读 + 唤醒）。所有路径参数校验由后端兜底。 */

export function getSandboxStatus(agentId: string, userId: string) {
  return http
    .get<SandboxStatusResponse>(`/agents/${agentId}/sandbox/status`, {
      params: { userId },
    })
    .then(r => r.data)
}

export function listSandboxFiles(
  agentId: string,
  userId: string,
  path: string,
  sessionId?: string,
) {
  return http
    .get<SandboxFileListResponse>(`/agents/${agentId}/sandbox/files`, {
      params: { userId, sessionId: sessionId ?? '', path },
    })
    .then(r => r.data)
}

export function readSandboxFileContent(
  agentId: string,
  userId: string,
  path: string,
  opts?: { offset?: number; limit?: number; sessionId?: string },
) {
  return http
    .get<SandboxFileContentResponse>(`/agents/${agentId}/sandbox/files/content`, {
      params: {
        userId,
        sessionId: opts?.sessionId ?? '',
        path,
        offset: opts?.offset ?? 0,
        limit: opts?.limit ?? 2000,
      },
    })
    .then(r => r.data)
}

/**
 * 构造 raw 端点的绝对 URL（用于 <img :src>、window.open 等需要直接访问的场景）。
 * axios 请求拦截器在 Electron 下会自动改写 url 走 IPC，这里直接拼接避免走 axios。
 */
export function buildSandboxRawUrl(
  agentId: string,
  userId: string,
  path: string,
  opts?: { download?: boolean; sessionId?: string },
): string {
  const params = new URLSearchParams({
    userId,
    sessionId: opts?.sessionId ?? '',
    path,
    download: opts?.download ? 'true' : 'false',
  })
  const pathPart = `/agents/${agentId}/sandbox/files/raw?${params.toString()}`
  // Electron 下用 window.electronAPI 同步拿到的后端 URL；否则用 vite dev/proxy 后的 /api 前缀
  // （http.baseURL 已经是绝对或相对，跟随 axios 同源）
  // 简化：用 location.origin + http.baseURL（去掉默认前缀 /api）
  const base =
    (typeof window !== 'undefined' && (window as any).electronAPI?.getBackendUrl?.()) ||
    (typeof window !== 'undefined' ? `${window.location.origin}/api` : '/api')
  return `${base.replace(/\/$/, '')}${pathPart}`
}

export function wakeSandbox(agentId: string, userId: string, sessionId?: string) {
  return http
    .post<SandboxStatusResponse>(
      `/agents/${agentId}/sandbox/wake`,
      null,
      { params: { userId, sessionId: sessionId ?? '' } },
    )
    .then(r => r.data)
}
