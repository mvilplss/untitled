'use strict'

const { contextBridge, ipcRenderer } = require('electron')

let cachedBackendUrl = 'http://localhost:8080'

ipcRenderer.on('backend:url', (_event, url) => {
  cachedBackendUrl = url
})

ipcRenderer.on('backend:status', (_event, _status) => {
  // noop — just keeping the listener registered; renderer subscribes via onBackendStatus
})

contextBridge.exposeInMainWorld('electronAPI', {
  getBackendUrl: () => cachedBackendUrl,
  getBackendStatus: () => ipcRenderer.invoke('backend:status'),
  restartBackend: () => ipcRenderer.invoke('backend:restart'),
  getConfig: () => ipcRenderer.invoke('config:get'),
  setConfig: (cfg) => ipcRenderer.invoke('config:set', cfg),
  onBackendStatus: (cb) => {
    const listener = (_event, status) => cb(status)
    ipcRenderer.on('backend:status', listener)
    return () => ipcRenderer.removeListener('backend:status', listener)
  },
  onBackendUrl: (cb) => {
    const listener = (_event, url) => cb(url)
    ipcRenderer.on('backend:url', listener)
    return () => ipcRenderer.removeListener('backend:url', listener)
  },
  openExternal: (url) => ipcRenderer.invoke('shell:open-external', url),
  platform: process.platform,
})