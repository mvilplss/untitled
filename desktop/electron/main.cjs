'use strict'

const { app, BrowserWindow, Menu, shell, dialog } = require('electron')
const path = require('node:path')
const fs = require('node:fs')
const { start: startSidecar, stop: stopSidecar, restart: restartSidecar, getStatus: getSidecarStatus } = require('./sidecar.cjs')
const { load: loadConfig, save: saveConfig } = require('./config.cjs')
const { registerIpc } = require('./ipc.cjs')

const isDev = !!process.env.VITE_DEV_SERVER_URL
const DESKTOP_DIR = path.join(__dirname, '..')
const RESOURCES_DIR = path.join(DESKTOP_DIR, 'resources')

let mainWindow = null
let sidecarOpts = null

function broadcastStatus(status) {
  if (!mainWindow || mainWindow.isDestroyed()) return
  try {
    mainWindow.webContents.send('backend:status', status)
  } catch {
    // 渲染帧在退出过程中可能已释放；忽略
  }
}

function broadcastUrl(url) {
  if (!mainWindow || mainWindow.isDestroyed()) return
  try {
    mainWindow.webContents.send('backend:url', url)
  } catch {
    // 渲染帧在退出过程中可能已释放；忽略
  }
}

function resolveBackendUrl() {
  const c = loadConfig()
  return c.mode === 'remote' ? c.remoteUrl : `http://localhost:${c.sidecarPort}`
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 800,
    minWidth: 960,
    minHeight: 600,
    title: '数字人平台',
    backgroundColor: '#0f1115',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      webSecurity: false,
    },
  })

  mainWindow.once('ready-to-show', () => {
    mainWindow.show()
  })

  if (isDev) {
    mainWindow.loadURL(process.env.VITE_DEV_SERVER_URL)
  } else {
    const indexPath = path.join(RESOURCES_DIR, 'app', 'index.html')
    mainWindow.loadFile(indexPath)
  }

  mainWindow.webContents.on('did-finish-load', () => {
    broadcastUrl(resolveBackendUrl())
    broadcastStatus(getSidecarStatus())
  })

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url)
    return { action: 'deny' }
  })

  mainWindow.on('closed', () => {
    mainWindow = null
  })
}

function buildMenu() {
  const isMac = process.platform === 'darwin'
  const template = [
    ...(isMac ? [{
      label: app.name,
      submenu: [
        { role: 'about' },
        { type: 'separator' },
        { role: 'services' },
        { type: 'separator' },
        { role: 'hide' },
        { role: 'hideOthers' },
        { role: 'unhide' },
        { type: 'separator' },
        { role: 'quit' },
      ],
    }] : []),
    {
      label: '文件',
      submenu: [
        isMac ? { role: 'close' } : { role: 'quit' },
      ],
    },
    {
      label: '编辑',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' },
      ],
    },
    {
      label: '视图',
      submenu: [
        { role: 'reload' },
        { role: 'forceReload' },
        { role: 'toggleDevTools' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' },
      ],
    },
    {
      label: '窗口',
      submenu: [
        { role: 'minimize' },
        { role: 'zoom' },
        ...(isMac ? [
          { type: 'separator' },
          { role: 'front' },
        ] : [
          { role: 'close' },
        ]),
      ],
    },
    {
      role: 'help',
      submenu: [
        {
          label: '关于',
          click: () => {
            dialog.showMessageBox(mainWindow, {
              type: 'info',
              title: '关于',
              message: '数字人平台 · 桌面客户端',
              detail: `Electron ${process.versions.electron}\nNode ${process.versions.node}\nChrome ${process.versions.chrome}\n\n后端策略：${loadConfig().mode}`,
            })
          },
        },
      ],
    },
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

function startBackendIfNeeded() {
  const cfg = loadConfig()
  if (cfg.mode !== 'sidecar') return
  const jarPath = path.join(RESOURCES_DIR, 'backend.jar')
  // 数据目录指向 Electron userData 下独立子目录，避免污染 JAR 安装目录
  const dataDir = path.join(app.getPath('userData'), 'data')
  try {
    fs.mkdirSync(dataDir, { recursive: true })
  } catch (err) {
    console.error('[sidecar] failed to create dataDir:', err && err.message)
  }
  sidecarOpts = {
    jarPath,
    port: cfg.sidecarPort,
    cwd: dataDir,
    onStatus: broadcastStatus,
  }
  startSidecar(sidecarOpts).catch(err => {
    console.error('[sidecar] start failed:', err && err.message ? err.message : err)
  })
}

app.whenReady().then(() => {
  registerIpc({
    getMainWindow: () => mainWindow,
    getSidecarOpts: () => sidecarOpts,
    broadcastStatus,
    broadcastUrl,
    resolveBackendUrl,
  })

  buildMenu()
  startBackendIfNeeded()
  createWindow()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

let isQuitting = false
app.on('before-quit', async (event) => {
  if (isQuitting) return
  isQuitting = true
  event.preventDefault()
  try {
    await stopSidecar()
  } catch (err) {
    console.error('[sidecar] stop error:', err)
  }
  app.exit(0)
})