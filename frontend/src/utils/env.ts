import type { ElectronAPI } from '@/types/electron'

/** 是否在 Electron 容器内（preload 注入了 window.electronAPI） */
export const inElectron: boolean =
  typeof window !== 'undefined' && !!(window as { electronAPI?: ElectronAPI }).electronAPI

/** Electron 渲染进程 IPC 桥（仅在 inElectron=true 时有值） */
export const electronAPI: ElectronAPI | undefined =
  typeof window !== 'undefined'
    ? (window as { electronAPI?: ElectronAPI }).electronAPI
    : undefined