# AGENTS

仓库级工作指引。通用 AI 编程规范在 `~/.config/opencode/AGENTS.md`。
全局能力清单（命令/子代理/技能/插件）见 `~/.config/opencode/opencode.json`。

## 项目概览

- **后端** Spring Boot 3.3.0（Java 17、Maven）+ AgentScope 2.0.3（harness + openai + channel-dingtalk 扩展）+ spring-webflux（钉钉 client 依赖）
- **前端** Vue 3 + TypeScript + Vite 5 + Element Plus 2 + Pinia + Vue Router 4 + Axios
- **桌面客户端** Electron 33（CommonJS 主进程）+ 复用现有 frontend/ 构建产物 + 后端 Spring Boot JAR 作为 sidecar 自动启停
- **三层结构** 后端在 `src/main/java/org/example/`，前端在 `frontend/`（独立 npm 项目、Vite 代理 `/api` → `:8080`），桌面壳在 `desktop/`（Electron 主进程 + sidecar + IPC）
- **入口**
  - 后端：`org.example.Application`（`mvn spring-boot:run` 启动 `:8080`）
  - 前端：`frontend/src/main.ts`（`cd frontend && npm run dev` 启动 `:5173`）
  - 桌面：`desktop/electron/main.cjs`（`cd desktop && npm run start` 启 Electron 窗口 + sidecar）

## 后端模块划分（包路径 `org.example.*`）

| 包 | 职责 |
|---|---|
| `agent/` | AgentSpec / AgentRegistry（运行时注册表 + Gateway 联动）/ AgentPersistence（json 落盘）/ AgentAdminController |
| `service/` | AgentService（流/同步对话）/ SessionService（扫磁盘 jsonl）/ ThinkingParser（剥离 <think> 块） |
| `web/` | ChatController（同步）/ StreamController（SSE）/ SessionController / GlobalExceptionHandler / `dto/` |
| `skill/` | SkillService / SkillController / SkillInfo |
| `bot/` | DingTalkBotConfig / DingTalkChannelRegistry（单例 GatewayBootstrap + ChannelManager）/ DingTalkBotService / DingTalkBotController |
| `config/` | AgentProperties（`agent.*` 配置）、ModelFactory、OpenApiConfig（Swagger） |

**REST 全集**：
```
POST/GET /api/agents           创建/列表
GET    /api/agents/{id}        详情
PUT    /api/agents/{id}        更新（重建 HarnessAgent）
DELETE /api/agents/{id}
GET    /api/agents/{id}/dingtalk   查询机器人配置（appSecret 掩码为 ***）
PUT    /api/agents/{id}/dingtalk   启/改机器人（立即启动/重启 Channel；"***" 视作沿用旧 secret）
DELETE /api/agents/{id}/dingtalk   停机器人并清除 spec.dingtalk 字段
POST   /api/chat               同步对话
GET    /api/chat/stream        SSE 流式对话（事件名：thinking / message / done）
GET    /api/agents/{id}/sessions                                  列表（含 preview）
GET    /api/agents/{id}/sessions/{sid}/messages                  历史
DELETE /api/agents/{id}/sessions/{sid}                           删除
GET    /api/skills                                                   列表
GET    /api/skills/{name}                                            详情
POST   /api/skills              multipart .zip / .skill 包
POST   /api/skills              JSON `{markdown}` 直接提交
POST   /api/skills/folder       multipart 多文件（来自 <input webkitdirectory>）
DELETE /api/skills/{name}
```

## 钉钉机器人（1:1 绑定数字人）

每个 Agent 可选配 1 个钉钉机器人（Stream 协议）。channel id 约定 `dingtalk-<agentId>`；所有消息路由到该 agent（`ChannelConfig.defaultAgentId = agentId`）。

- **依赖**：`io.agentscope:agentscope-extensions-channel-dingtalk` + `spring-boot-starter-webflux`（钉钉 client 用 WebClient）
- **核心组件** `DingTalkChannelRegistry`：单例 GatewayBootstrap + ChannelManager。`initialize(List<HarnessAgent>)` 用持久化 agent 作种子；持久化为空时调 `initializeWithStub()` 构造占位 stub（main 永不路由）
- **生命周期**：创建/更新/删除 Agent 时同步 `gateway.registerAgent`；启/停机器人调 `channelRegistry.apply(agentId, cfg)`，内部 `channelManager.register + channel.init + channel.start`
- **持久化**：`AgentSpec.dingtalk` 字段直接落 `.agentscope/agents.json`；`AgentPersistence` 走"字段可见性"路径（不走 getter）以保留明文 secret
- **前端掩码**：`AgentSpec.getDingtalk()` 返回 `masked()` 副本（appSecret 一律 ***）；`getDingtalkRaw()` 给 BotService 内部读取
- **重启恢复**：启动时 `loadFromPersistence()` 末尾遍历 spec.dingtalk 调 `apply(...)`，失败单条 warn 不影响其他
- **僵尸 channel 防护**：删除/更新 Agent 时先 `channelRegistry.removeQuietly(agentId)` 停 channel
- **前端 UI**：`AgentFormDialog.vue` 仅在编辑模式下显示"// 钉钉机器人"独立子区块；开关 + AppKey/AppSecret/RobotCode；`AppSecret` 输入框带"显示/隐藏"切换，提交时传 *** 沿用旧值

## 前端模块划分

- `frontend/src/main.ts` 全局注册 Pinia + Router + ElementPlus + **全部 icons**（@element-plus/icons-vue 全量 `app.component`）
- `views/` AgentsView / ChatView / SkillsView；路由默认 `/` → `/agents`
- `stores/` agents / chat / skills；`api/` client.ts（axios 实例 + 响应拦截器统一 ElMessage 报错）
- `components/` AgentFormDialog（创建/编辑 Agent + 工具/技能多选）/ MessageBubble
- `types/api.ts` 共享类型；`types/tools.ts` 工具元数据

## 关键命令

| 操作 | 命令 |
|---|---|
| 后端启动 | `mvn -q spring-boot:run`（后台：`nohup mvn -q spring-boot:run > /tmp/backend.log 2>&1 &`） |
| 后端编译 | `mvn -q compile` |
| 后端打包（生成可执行 JAR） | `mvn -q -DskipTests package` → `target/untitled-1.0-SNAPSHOT.jar` |
| 前端启动 | `cd frontend && npm run dev` |
| 前端类型检查 | `cd frontend && npx vue-tsc --noEmit` |
| 前端构建 | `cd frontend && npm run build` |
| 桌面客户端启动 | `cd desktop && npm install && npm run start`（自动 prepare:frontend + prepare:backend + electron） |
| 桌面开发模式（HMR） | `cd desktop && npm run dev` |
| 桌面仅重打前端 | `cd desktop && npm run prepare:frontend` |
| 桌面仅重打后端 | `cd desktop && npm run prepare:backend` |
| 端口检查/释放 | `lsof -i :8080` / `lsof -i :5173` |
| 杀进程 | `pkill -f "org.example.Application"` 或 `pkill -f "spring-boot:run"` 或 `pkill -f "Electron Helper"` |

## 工具链细节（容易踩坑）

- **Maven wrapper 不存在**，需要本机 mvn（当前 3.8.6）
- **无 README**，新人上手主要靠 Swagger UI（`http://localhost:8080/swagger-ui.html`）
- **无 git 仓库**，但 `.gitignore` 已写好；不要 `git status`，直接改文件
- **API Key 真实值在 `src/main/resources/application-local.yml`**，已被 gitignore；
  仓库里只有 `application-local.yml.example` 模板，请复制并填入
- **profiles 默认 `local`**，启动必须能读到 `application-local.yml`，否则 LLM 调不通
- **Java 17 / Node v24**；后端跑在 macOS JDK 25（向后兼容）

## AgentScope 框架坑（2.0.3 release 版）

- **ToolsConfig 没有 `strictAllow`/`setDefaultToolsEnabled`**（仅 SNAPSHOT 版有）。Allow 白名单是核心机制，启用即生效。
- **SkillFilter.only/except/all/none** 在 release 版都可用，配合 `builder.skillFilter(...)` 注入。
- **`SkillsConfig.builder.projectGlobalSkillsDir(Path)`** 注册全局 skill 仓库，配合 SkillFilter 实现按 agent 可见性过滤。
- **会话是懒创建**：stream/sync API 首次被调用即开始写 `<sessionId>.jsonl`，无需独立 create 接口。
- **`SkillUtil.createFromZip` 要求 zip 内单根目录**——macOS Finder 导出的 zip 含 `__MACOSX/` 噪音，`SkillService.createFromZip` 已在调用前 `stripAppleDoubleEntries` 清洗。
- **HarnessAgent.streamEvents(Msg, RuntimeContext)** 重载传 sessionId+userId；不要忘了 RuntimeContext，否则 session 不持久化。
- **GatewayBootstrap 强制至少 1 个 agent**——`DingTalkChannelRegistry` 采用两阶段构造：`initialize(seeds)` / `initializeWithStub()`，由 `AgentRegistry.loadFromPersistence()` 在加载完 spec 后调用。持久化列表为空时构造 OpenAI 占位 stub 作 main（URL 是 127.0.0.1:1，永远不会被路由）。
- **DingTalkChannel 在 `Channel.start()` 内异步建 WebSocket**——`DingTalkStreamClient` 自带指数退避重连，所以 PUT /api/agents/{id}/dingtalk 返回 200 不等于钉钉开放平台侧 stream 已连通。要验证连通性需看日志中的 access_token / stream 注册成功行。

## SSE 流式约定

- `event:thinking`           → 思考块 delta
- `event:message`            → 正文 delta
- `event:tool_start`         → `{"id","name"}` 工具调用开始
- `event:tool_delta`         → `{"id","delta"}` 工具入参增量（JSON 片段）
- `event:tool_end`           → `{"id"}` 工具入参结束
- `event:tool_result`        → `{"id","name","state","output"}` 工具执行完成
- `event:done`               → 流结束哨兵（前端据此关闭 EventSource）
- 后端 `StreamController.stream(...)` 返回 `Flux<ServerSentEvent<String>>`
- 同步 `POST /api/chat` 返回 `ChatResponse{reply, thinking, toolCalls[]}`
- 前端用 `ToolCallBlock` 组件展示（**默认折叠**，点击展开 arguments / output）

## 文件上传约定

- **zip**（multipart file）：`Content-Type: multipart/form-data; boundary=...`（**axios 不要显式设 Content-Type**，会自动带 boundary）
- **markdown**：`POST /api/skills` + JSON `{markdown: "..."}`
- **文件夹**：`POST /api/skills/folder` + 多 multipart file；filename 用 webkitRelativePath，Spring `getOriginalFilename()` 自动还原
- 前端默认 file 大小限制：`application.yml` 已设 `max-file-size: 50MB` / `max-request-size: 200MB`

## `.agentscope/` 数据持久化策略（**重要**）

目录结构：
```
.agentscope/
├── agents.json          ← Agent 持久化定义（gitignore）
├── skills/              ← 上传的 Skill（SKILL.md 必填）
└── workspace/<agentId>/ ← Agent 运行时数据 + 会话 jsonl
```

**清理策略**：
- 默认**保留**——agents.json、skills/、workspace/ 都是用户数据
- 只有出现**不兼容变更**（DTO 加必填字段、路径/目录结构改动、schema 版本升级）时才清空，并说明原因
- 集成测试用临时 `agentId: temp-*`，验证完 `DELETE` 清理自己造的临时数据

## 桌面客户端（Electron + Sidecar）

`desktop/` 目录提供 Electron 壳，跑前端（复用 `frontend/`）+ 后端（Spring Boot JAR 作为 sidecar 启停）。目标：一次 `npm run start` 起一个完整桌面窗口，零外部依赖。

**目录结构**
```
desktop/
├── package.json               # electron 依赖、脚本（无 frontend 重复依赖）
├── electron/
│   ├── main.cjs               # 窗口 / 菜单 / 生命周期 / 装配 sidecar + IPC
│   ├── preload.cjs            # contextBridge 暴露 electronAPI（含 cachedBackendUrl 同步读）
│   ├── sidecar.cjs            # java -jar backend.jar 启停 + 健康探测（60s 超时）
│   ├── config.cjs             # userData/config.json 持久化
│   └── ipc.cjs                # ipcMain.handle 全部注册
├── scripts/
│   ├── prepare-frontend.cjs   # 调 frontend 的 npm run build → 拷贝 dist 到 resources/app/
│   ├── prepare-backend.cjs    # 调根目录 mvn package → 拷贝 untitled-*.jar 到 resources/backend.jar
│   └── dev.cjs                # 并发拉起 vite dev server + sidecar + electron（HMR）
└── resources/                 # 运行时资源（gitignore）
    ├── backend.jar            # 由 prepare:backend 注入
    ├── app/                   # 由 prepare:frontend 注入
    └── icon/                  # 图标占位
```

**前端侧如何识别 Electron**
- `vite.config.ts` 已设 `base: './'`（兼容 vite dev 与 `loadFile` 两种场景）
- `frontend/src/types/electron.d.ts` 声明 `window.electronAPI` 类型
- `frontend/src/api/client.ts` 检测 `window.electronAPI` 时把 axios 请求路径换成绝对 URL
- `frontend/src/api/chat.ts` 的 SSE URL 同上；`openChatStream` 已改为 sync（preload 缓存 URL，零 IPC 延迟）
- `frontend/src/router/index.ts` 在 Electron 下用 hash 模式（`createWebHashHistory`），避免 `file://` 下 HTML5 history 丢上下文
- `frontend/src/components/DesktopSettings.vue` 是设置抽屉（后端模式 / 端口 / 远程 URL / 重启）

**配置持久化**
- 路径：`app.getPath('userData')/config.json`（macOS `~/Library/Application Support/untitled-agent-desktop/`，Windows `%APPDATA%/untitled-agent-desktop/`，Linux `~/.config/untitled-agent-desktop/`）
- 默认值：`{ mode: 'sidecar', sidecarPort: 8080, remoteUrl: 'http://localhost:8080' }`
- 设置面板里改完保存会立即生效，无需重启应用

**Sidecar 数据目录**
- cwd = `<userData>/data/`（Spring Boot 在此目录下读写 `.agentscope/agents.json`、`.agentscope/skills/`、`.agentscope/workspace/...`）
- 避免污染 JAR 安装目录；卸载 Electron 应用时数据保留在 userData 下

**Sidecar 生命周期**
- 主进程 `app.whenReady()` 后根据 `config.mode` 决定是否 spawn `java -jar`
- 健康探测：每 500ms GET `/api/agents`，最多 60s
- 启动失败会在状态指示里显示错误（含原因：JAR 缺失 / java 不在 PATH / 端口占用）
- 退出路径：`app.before-quit` → `sidecar.stop()`（SIGTERM → 3s 后 SIGKILL 兜底）

**安全**
- 主窗口 `webPreferences`: `contextIsolation: true`, `nodeIntegration: false`, `webSecurity: false`
- `webSecurity: false` 仅为了 desktop 场景下避免给后端加 CORS；可访问的 URL 全程是本机 localhost + 配置的 remote
- 外链一律走 `shell.openExternal`，不在 Electron 窗口里打开

**已知坑**
- `VITE_DEV_SERVER_URL` 存在时主进程走 vite dev server（`http://localhost:5173`），便于 HMR；不存在时走 `loadFile(resources/app/index.html)`
- 第一次启动 `desktop/` 必须 `npm install`（安装 electron 二进制）
- 后端 `application-local.yml` 含真实 API Key，会被打入 sidecar JAR；如要分发安装包需要单独处理

## 关键惯例

- **Swagger 注解**：每个 controller 用中文 `@Operation` + `@Tag`；全局异常走 `GlobalExceptionHandler`，`@RequestParam` 缺 part 抛 `MissingServletRequestPartException` → 400
- **多租户/路径安全**：所有 sessionId / skill name 走 `^[a-zA-Z0-9_-]{1,64}$`，杜绝路径穿越
- **前端 HMR element-plus 陷阱**：第三方组件（el-upload 等）实例在 HMR 时不重置。涉及上传组件改动后建议让用户硬刷（Cmd+Shift+R）
- **环境配置层**：`application-local.yml.example` 是模板；`application-local.yml` 是用户私有（含 API Key）；改配置优先改 gitignore 列表外的 yaml 文件

## 上手检查清单（首次或重大改动后）

1. `mvn -q compile` 通过
2. `cd frontend && npx vue-tsc --noEmit` 通过
3. 后端启动：浏览器访问 `http://localhost:8080/swagger-ui.html` 应能列出所有 API
4. 前端启动：`http://localhost:5173/` 应能跳到 `/digital-humans`
5. 创建一个 Agent → 选模型发消息 → 看 SSE 流 → 检查 `.agentscope/workspace/<agentId>/.../sessions/*.jsonl` 是否落盘
6. 桌面客户端：`cd desktop && npm install && npm run start` → 弹出 Electron 窗口 → 侧栏后端状态显示「已连接」→ 三视图可正常操作