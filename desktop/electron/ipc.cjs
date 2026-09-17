'use strict'

const path = require('node:path')
const { ipcMain, shell } = require('electron')
const { load: loadCfg, save: saveCfg } = require('./config.cjs')
const { start: startSidecar, stop: stopSidecar, restart: restartSidecar, getStatus: getSidecarStatus } = require('./sidecar.cjs')

function register({ getMainWindow, getSidecarOpts, broadcastStatus, broadcastUrl, resolveBackendUrl }) {
  ipcMain.handle('config:get', () => loadCfg())

  ipcMain.handle('config:set', (_event, cfg) => {
    const saved = saveCfg(cfg)
    broadcastUrl(resolveBackendUrl())
    return saved
  })

  ipcMain.handle('backend:status', () => getSidecarStatus())

  ipcMain.handle('backend:url', () => resolveBackendUrl())

  ipcMain.handle('backend:restart', async () => {
    const opts = getSidecarOpts && getSidecarOpts()
    if (!opts) {
      return { status: 'error', port: 0, error: '当前为 remote 模式，没有本地 sidecar 可重启' }
    }
    try {
      await restartSidecar(opts)
      return getSidecarStatus()
    } catch (err) {
      return { status: 'error', port: opts.port, error: err && err.message ? err.message : String(err) }
    }
  })

  ipcMain.handle('shell:open-external', (_event, url) => {
    if (typeof url !== 'string') return Promise.resolve(false)
    return shell.openExternal(url)
  })
}

module.exports = { registerIpc: register }