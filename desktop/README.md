# 数字人平台 · 桌面客户端

Electron 壳 + 复用 `../frontend/` Vue 3 项目 + 后端 Spring Boot JAR 作为 sidecar 自动启停。
一句话——`mvn package && npm run start` 起一个窗口，连后端，能跑三视图。

## 目录

```
desktop/
├── package.json              # electron 依赖、脚本
├── electron/
│   ├── main.cjs              # 主进程：窗口 / 菜单 / 生命周期 / 装配 sidecar + IPC
│   ├── preload.cjs           # contextBridge 暴露 electronAPI
│   ├── sidecar.cjs           # java -jar backend.jar 启停 + 健康探测
│   ├── config.cjs            # userData/config.json 持久化
│   └── ipc.cjs               # ipcMain.handle 全部注册
├── scripts/
│   ├── prepare-frontend.cjs  # 调 frontend 的 npm run build，dist 拷到 resources/app/
│   ├── prepare-backend.cjs   # 调根目录 mvn package，jar 拷到 resources/backend.jar
│   └── dev.cjs               # 并发拉起 vite dev server + sidecar + electron
└── resources/                # 运行时资源（gitignore）
    ├── backend.jar           # 由 prepare:backend 注入
    ├── app/                  # 由 prepare:frontend 注入（Vue 构建产物）
    └── icon/                 # 应用图标（占位）
```

## 命令

| 操作 | 命令 |
|---|---|
| 首次构建 | `npm run start`（会自动 `prepare:frontend` + `prepare:backend`） |
| 后续启动 | `electron .`（已构建过） |
| 开发模式（HMR） | `npm run dev` |
| 只重新构建前端 | `npm run prepare:frontend` |
| 只重新构建后端 | `npm run prepare:backend` |

## 后端连接策略

- 默认 `mode=sidecar`，主进程自动 `spawn('java', ['-jar', 'backend.jar'])` 启 `:8080`，窗口关闭时 kill
- 设置面板可切到 `mode=remote`，填 `http://your-host:port`，sidecar 不启
- 配置存 `app.getPath('userData')/config.json`

## 前端侧如何识别 Electron

- `vite.config.ts` 已设 `base: './'`，dist 能直接 `loadFile`
- `frontend/src/types/electron.d.ts` 声明 `window.electronAPI`
- `frontend/src/api/client.ts` 检测到 `window.electronAPI` 时改用绝对 URL（`http://localhost:8080/api`）
- `frontend/src/api/chat.ts` 的 SSE URL 同上，`openChatStream` 改为 async

## 安全说明

- 主窗口 `webSecurity: false`（desktop-only 场景，避免给后端加 CORS）
- `contextIsolation: true` + `nodeIntegration: false`（preload 通过 `contextBridge` 暴露最小 API）
- `setWindowOpenHandler` 把外链交给系统浏览器

## 平台差异

- macOS：`Cmd+Q` 走 `before-quit` → `sidecar.stop()`；不关后台进程
- Windows / Linux：所有窗口关闭即 `app.quit()` → `sidecar.stop()`