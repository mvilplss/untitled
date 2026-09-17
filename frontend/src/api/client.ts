import axios, { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiError } from '@/types/api'

const inElectron =
  typeof window !== 'undefined' && !!window.electronAPI

const DEFAULT_BASE = inElectron ? 'http://localhost:8080/api' : '/api'

export const http = axios.create({
  baseURL: DEFAULT_BASE,
  timeout: 120_000,
})

// 在 Electron 下，每次请求自动把相对路径换成绝对后端 URL。
// 走 IPC 同步取值（preload 维护 cachedBackendUrl，零延迟）。
http.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  if (inElectron && window.electronAPI && config.url && !/^https?:\/\//.test(config.url)) {
    const base = window.electronAPI.getBackendUrl().replace(/\/$/, '')
    const path = config.url.startsWith('/') ? config.url : `/${config.url}`
    config.url = `${base}/api${path}`
  }
  return config
})

http.interceptors.response.use(
  (resp: AxiosResponse) => resp,
  (error: AxiosError<ApiError>) => {
    const status = error.response?.status
    const message = error.response?.data?.error || error.message || '请求失败'
    const finalMsg = status ? `[${status}] ${message}` : message
    ElMessage({
      type: 'error',
      message: finalMsg,
      duration: 4000,
      showClose: true,
    })
    return Promise.reject(new Error(finalMsg))
  }
)