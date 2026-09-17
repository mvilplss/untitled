export interface DesktopBackendStatus {
  status: 'idle' | 'starting' | 'ready' | 'error'
  port?: number
  error?: string | null
}

export interface DesktopConfig {
  mode: 'sidecar' | 'remote'
  sidecarPort: number
  remoteUrl: string
}

export interface ElectronAPI {
  getBackendUrl: () => string
  getBackendStatus: () => Promise<DesktopBackendStatus>
  restartBackend: () => Promise<DesktopBackendStatus>
  getConfig: () => Promise<DesktopConfig>
  setConfig: (cfg: DesktopConfig) => Promise<DesktopConfig>
  onBackendStatus: (cb: (s: DesktopBackendStatus) => void) => () => void
  onBackendUrl: (cb: (url: string) => void) => () => void
  openExternal: (url: string) => Promise<boolean>
  platform: NodeJS.Platform
}

declare global {
  interface Window {
    electronAPI?: ElectronAPI
  }
}

export {}