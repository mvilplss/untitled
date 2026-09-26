# AGENTS

仓库级工作指引。通用 AI 编程规范在 `~/.config/opencode/AGENTS.md`。
全局能力清单（命令/子代理/技能/插件）见 `~/.config/opencode/opencode.json`。

## 项目概览

- **后端** Spring Boot 3.3.0（Java 17、Maven）+ AgentScope 2.0.3（harness + openai + channel-dingtalk + rag-bailian 扩展）+ spring-webflux（钉钉 client 依赖）
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
| `bailian/` | BailianRagConfig / BailianRagService（构建 BailianKnowledge + KnowledgeRetrievalTools，按 agentId 缓存）/ BailianRagController |
| `sandbox/` | KeepAlive*Sandbox（延迟销毁）/ SandboxKeepAliveManager / **SandboxFileService + SandboxFileController + `dto/`（沙箱文件浏览器）** |
| `config/` | AgentProperties（`agent.*` 配置，含 `agent.bailian` 全局默认）/ ModelFactory、OpenApiConfig（Swagger） |

**REST 全集**：
```
POST/GET /api/agents           创建/列表
GET    /api/agents/{id}        详情
PUT    /api/agents/{id}        更新（重建 HarnessAgent）
DELETE /api/agents/{id}
GET    /api/agents/{id}/dingtalk   查询机器人配置（appSecret 掩码为 ***）
PUT    /api/agents/{id}/dingtalk   启/改机器人（立即启动/重启 Channel；"***" 视作沿用旧 secret）
DELETE /api/agents/{id}/dingtalk   停机器人并清除 spec.dingtalk 字段
GET    /api/agents/{id}/bailian    查询百炼 RAG 配置（accessKeySecret 掩码为 ***）
PUT    /api/agents/{id}/bailian    启/改 RAG（重建 HarnessAgent 注入 retrieve_knowledge 工具；"***" 视作沿用旧 secret；缺失字段回退到 application.yml 全局默认）
DELETE /api/agents/{id}/bailian    停用 RAG 并清除 spec.bailian 字段
POST   /api/chat               同步对话
POST   /api/chat/stream        SSE 流式对话（事件名：thinking / message / tool_* / usage / done）
GET    /api/agents/{id}/sessions                                  列表（含 preview）
GET    /api/agents/{id}/sessions/{sid}/messages                  历史
DELETE /api/agents/{id}/sessions/{sid}                           删除
GET    /api/skills                                                   列表
GET    /api/skills/{name}                                            详情
POST   /api/skills              multipart .zip / .skill 包
POST   /api/skills              JSON `{markdown}` 直接提交
POST   /api/skills/folder       multipart 多文件（来自 <input webkitdirectory>）
DELETE /api/skills/{name}
GET    /api/agents/{id}/sandbox/status?userId=                  沙箱运行状态（不 acquire、不 docker I/O）
GET    /api/agents/{id}/sandbox/files?userId=&path=&sessionId=  目录列表（懒加载树节点）
GET    /api/agents/{id}/sandbox/files/content?userId=&path=&offset=&limit=&sessionId=   文本/代码预览（utf-8 / base64）
GET    /api/agents/{id}/sandbox/files/raw?userId=&path=&download=&sessionId=   原始字节（图片 / 下载，按扩展名 MIME + X-Content-Type-Options: nosniff）
POST   /api/agents/{id}/sandbox/wake?userId=&sessionId=         显式唤醒：Priority 3 resume + start；state 缺失时 Priority 4 新建
POST   /api/agents/{id}/chat/upload?userId=&sessionId=          上传聊天附件（multipart，返回 attachments 列表）
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
- **前端 UI**：`AgentFormDialog.vue` 组合 `DingTalkSubForm` + `SkillMultiPicker`，仅在编辑模式下钉钉区显示独立保存按钮（启用并启动 / 停用机器人）；开关 + AppKey/AppSecret/RobotCode；`AppSecret` 输入框带"显示/隐藏"切换，提交时传 *** 沿用旧值

## 前端模块划分

- `frontend/src/main.ts` 全局注册 Pinia + Router + 全量 icons（@element-plus/icons-vue）；组件由 vite unplugin-vue-components 自动按需注册
- `views/` AgentsView / ChatView / SkillsView；路由默认 `/` → `/digital-humans`
- `stores/` agents / chat / skills；`api/` client.ts（axios 实例 + 响应拦截器统一 ElMessage 报错）
- `components/` AgentFormDialog（创建/编辑 Agent，组合 SkillMultiPicker + DingTalkSubForm + BailianRagSubForm） / DesktopSettings / ReasoningPanel / ToolCallBlock
- `utils/` format（pad2/pad3/formatRelative）+ env（inElectron 常量）
- `types/api.ts` 共享类型；`types/electron.d.ts` 声明 window.electronAPI；`types/tools.ts` 工具元数据

## 阿里云百炼 RAG（per-Agent 知识检索）

每个 Agent 可选配 1 个百炼知识库。启用后 `BailianRagService` 构造自实现的 `BailianKnowledgeTool`（标注 `@Tool(name="retrieve_knowledge")`）注入到 `HarnessAgent.getToolkit()`，Agent 自主决定何时检索。

- **依赖**：`io.agentscope:agentscope-extensions-rag-bailian`（传递 `com.aliyun:bailian20231229`）
- **配置层级**：`application.yml` 全局默认（`agent.bailian.*`）+ `AgentSpec.bailian` per-agent 覆盖。`accessKeyId/accessKeySecret/endpoint/limit/scoreThreshold/enableRerank/enableRewrite` 可走全局；`workspaceId/indexId` 必须 per-agent 提供
- **核心组件**
  - `BailianRagService.buildTools(agentId, cfg)`：按 agentId 缓存 `BailianKnowledgeTool`（spec 变更时 `invalidate`）
  - `BailianKnowledgeTool.retrieveKnowledge(query, topK?, scoreThreshold?)`：自实现的 `@Tool` 方法，直接调 `BailianClient.retrieve(workspaceId, indexId, k)` 并解析 `com.aliyun.bailian20231229.models.RetrieveResponse` 为 LLM 友好的文本
- **生命周期**：创建/更新 Agent 时同步重建 HarnessAgent；`AgentRegistry.upsertBailian` / `disableBailian` / `rebuildAgent` 闭环
- **持久化**：`AgentSpec.bailian` 字段直接落 `.agentscope/agents.json`（字段直读不走 getter）
- **前端掩码**：`AgentSpec.getBailian()` 返回 `masked()` 副本（accessKeySecret 一律 ***）；`getBailianRaw()` 给 BailianRagService 内部读取
- **重启恢复**：启动时 `loadFromPersistence()` 经过 `buildAgent()` 自动注入工具，无需额外步骤
- **前端 UI**：`AgentFormDialog.vue` 组合 `BailianRagSubForm`；编辑模式独立「启用并启动 / 停用 RAG」按钮；新建模式随 payload 一起 POST。子表单含基础字段（AK / Secret / WorkspaceId / IndexId）+ 折叠的高级字段（Endpoint / TopK / 阈值 / rerank / rewrite / rerankModel / rerankMinScore / rerankTopN / rewriteModel）
- **sysPrompt 提示**：UI 子表单 hint 中建议在系统提示词中加入「需要时使用 retrieve_knowledge 工具检索知识库」以提高 agentic 检索命中率

## ⚠️ v2 RAG API 追踪

**当前状态（2.0.3）**：AgentScope Java 2.0 的 v1 RAG 接口（`Knowledge` / `KnowledgeRetrievalTools` / `RAGMode` / `GenericRAGHook` 以及 `ReActAgent.Builder.knowledge(...).ragMode(...)` / `.retrieveConfig(...)`）全部标 `@Deprecated(forRemoval = true, since = "2.0.0")`。官方迁移指南 [B.5](https://java.agentscope.io/v2/en/docs/change-log.md) 明确说「The v2 rewrite is underway. New knowledge base, document reader, and store APIs will land in subsequent minor releases」。

**本仓库已主动避开所有 v1 RAG 类型**：自实现的 `BailianKnowledgeTool` 直接调 `BailianClient.retrieve(...)` + 解析 `RetrieveResponse`，并通过 `Toolkit.registerTool(Object)` + `@Tool`/`@ToolParam` 注解注册。`grep -E 'KnowledgeRetrievalTools|io\.agentscope\.core\.rag\.Knowledge\\b|RAGMode' src/` 在代码里只剩注释/JavaDoc 引用，无任何 import 或调用。

**追踪动作**：当 AgentScope 发布 v2 RAG API（预估在 2.1 或更新版本），按以下步骤迁移：
1. 看新包结构是否提供 `BailianKnowledgeV2` / `RagService` 等替代类
2. 替换 `BailianKnowledgeTool.retrieveKnowledge` 的实现（API 签名应保持 `String query, Integer topK, Double scoreThreshold` 不变以兼容前端 sysPrompt 提示）
3. `Toolkit.registerTool(tool)` 调用方式不变（注解驱动的反射注册是 v2 通用机制）
4. 删除 `BailianKnowledgeTool` 自实现类（如新 SDK 提供等价 `@Tool` 实现）

**回归检查清单**（每次 AgentScope 升级后跑一遍）：
- `mvn -q compile -Dmaven.compiler.showDeprecation=true` 是否有新增 deprecation 警告
- `unzip -l agentscope-core-*.jar | grep -iE 'RagService|KnowledgeBase|KnowledgeRetriever'` 看是否出现新接口
- 官方 [Release Notes](https://java.agentscope.io/v2/en/docs/others/release-notes.md) 与 [Migration Guide](https://java.agentscope.io/v2/en/docs/change-log.md) 是否把 B.5 从「in progress」改为已落地

## 聊天附件（图片/文件上传）

每个 Agent 支持在对话中上传图片和文件。**图片走多模态**（base64 inline 到 LLM），**其他文件走路径引用**（Agent 用 read 工具读）。

**上传流程**：
1. 前端 `POST /api/agents/{id}/chat/upload`（multipart）上传文件到**后端 host 暂存目录** `~/.agentscope/chat-uploads/<agentId>/<userId>/<originalname>`（同名文件自动加 ` (1)`、` (2)` 序号）
2. 返回 `{attachments: [{id, name, path, size, mime, containerPath}]}` 列表
3. 前端将 attachments 作为 `ChatRequest.attachments` 随消息一起发送
4. 后端 `AgentService` 在 `ChatMessageBuilder.build()` **之前**，通过 `SandboxFileService.uploadAttachments()` 将文件 `docker cp` 到沙箱容器 `/workspace/uploads/<userId>/<uuid>-<name>`
5. `ChatMessageBuilder.build()` 构造 `UserMessage`：图片 → `DataBlock(Base64Source)` 多模态；其他文件 → 路径引用提示拼到文本

**沙箱可见性**（**无 bind mount**，走 `docker cp`）：
- 文件通过框架 `AbstractFilesystem.uploadFiles()` → `SandboxFileTransfer.uploadFile()` → `docker cp` 传入容器
- `docker cp` 通过 Docker daemon 协议传输，**不受 backend host 与 Docker daemon host 是否同机限制**
- 远程 Docker：设 `DOCKER_HOST=tcp://remote:2375`（或 `ssh://user@remote`）即可，行为一致
- 容器内路径：`/workspace/uploads/<userId>/<originalname>`
- Agent 可在沙箱里 `shell` / `read` / `dws` 操作上传的文件

**大小限制**：图片 10MB，其他文件 50MB。支持扩展名：`image/*, .pdf, .txt, .md, .docx, .xlsx, .csv, .json, .log`

**前端 UI**：textarea 上方显示附件 chips；📎 按钮触发文件选择；支持拖拽 + Ctrl+V 粘贴图片；Enter 发送（有附件时可空文本发送）

**历史回显**：附件记录写 sidecar `.attachments.jsonl`（`workspaceRoot/<agentId>/usage/<sessionId>.attachments.jsonl`），`SessionService.getHistory` 时按 USER 消息顺序挂回

## Docker Sandbox（per-Agent 容器）

`AgentRegistry.buildAgent` 给每个 Agent 挂一个 `DockerFilesystemSpec`（`AgentRegistry.java`），镜像 `untitled-sandbox:latest`，隔离模式 `IsolationScope.USER`（每个 user 一个 sandbox 容器）。**所有配置通过 `agent.sandbox.*` 注入**：

```yaml
agent:
  sandbox:
    memory: 4g        # docker run --memory；不设会沿用 Docker Desktop 默认上限
    cpu: 2.0          # docker run --cpus
    network: bridge   # dws 要调钉钉 OpenAPI，必须有外网；none=完全隔离（dws 不可用）
    image: untitled-sandbox:latest
    dws-credentials-dir: ~/Library/Application Support/dws-cli  # → 容器 /root/.local/share/dws-cli
    dws-config-dir: ~/.dws                                      # → 容器 /root/.dws
    snapshot-root: ~/.agentscope/docker-snapshots               # LocalSnapshotSpec 根目录
```

底层入口：`AgentProperties.Sandbox` → `AgentRegistry.parseMemoryToBytes(...)` / `expandHome(...)` → `DockerFilesystemSpec.memorySizeBytes(Long) / cpuCount(Long) / network(String)` + `additionalRunArgs(...)`。**memory 解析支持 `g/m/k/b` 单位**，非法值返回 null（沿用 Docker 默认）并 warn。路径字段支持 `~` 前缀展开，留空 = 不挂载。

**为什么不设上限会出事**：`DockerSandbox` 默认无 `--memory`，容器跑 `tar -xf -` 解压大 jsonl（`SessionTree.mirrorToFilesystem` 每条 entry 都触发全文件上传）时会被宿主机/Docker Desktop cgroup OOM-killer → `exit=137`。设了 `--memory=4g` 后内存受控，tar 进程被 OOM 会明确触发 cgroup 限制而非随机 SIGKILL，更可控。

**网络 & 自定义镜像**：

- `agent.sandbox.network: bridge` 容器经 Docker Desktop VM NAT 访问外网；`none` 是完全隔离。`fsSpec.network()` 已显式接线（此前配置未生效，靠 docker 默认 bridge 兜底）
- `agent.sandbox.image: untitled-sandbox:latest` 自定义镜像 = `node:22-bookworm-slim` + 真实 dws（`dingtalk-workspace-cli@1.0.62`，npm 包装 + 平台原生 vendor 二进制）+ Python 3 + unzip（dws postinstall 解 skills 包必需）+ dumb-init。构建脚本 `docker/sandbox.Dockerfile`
- 硬编码 hardening（`AgentRegistry.buildSandboxAdditionalRunArgs`）：`--cap-drop=ALL --cap-add=SETGID --cap-add=SETUID --security-opt=no-new-privileges --tmpfs /tmp:512m`
- **平台**：镜像按 host 架构构建（Apple Silicon = linux/arm64），npm 自动取对应平台的 vendor 二进制。**不再锁 `--platform=linux/amd64`**（旧方案的 amd64 预下载 tarball 已删除）
- **沙箱镜像缓存坑**：`SessionSandboxStateStore` 把首次创建容器的 image / network 写到 `~/.agentscope/state/<id>/__anon__/<base64url(slotSid)>/_sandbox_state.json`（`<id>` 来自 `AgentRegistry.buildAgent` 显式调 `.agentId(spec.getId())`）。**改 image/network 配置后必须删该 state 才能生效**（harness resume 持久化 state 时不重新读 spec）。手动清理命令：`find ~/.agentscope/state -name _sandbox_state.json -delete`
- **agent namespace key 统一为 `spec.getId()`**：`HarnessAgent.Builder` 默认 `agentId` 字段为 null，`build()` 会 fallback 到 `name`——而 `name` 可改、可重名，会让沙箱 state 目录与 slotSid（`sandbox/user/<key>/<userId>`）漂移，导致文件浏览看到的是与对话**不同**的容器。`AgentRegistry.buildAgent` 已**显式**调 `.agentId(spec.getId())` 把 namespace key 锚定到不可变的 spec id；任何新增 harness 路径也都应传 `spec.getId()`，不要再回退到 `spec.getName()`。

**容器内 dws 与 host dws 共享登录态（凭证 bind-mount）**：

容器内 dws 是镜像里 npm 装的**真实二进制**（`dingtalk-workspace-cli`），与宿主 dws **版本对齐（1.0.62）**避免凭证格式不兼容。登录态通过 bind-mount 共享，容器内 `dws xxx` ≡ 宿主终端 `dws xxx`。

**架构**：
- `docker run` 两个 **rw** 挂载（`:ro` 不行——token 自动刷新要写回）：
  1. `~/Library/Application Support/dws-cli` → `/root/.local/share/dws-cli`：`auth-token*.enc` + `dek` 一起进容器（同目录 file-DEK，Linux 版 dws 可直接解密）
  2. `~/.dws` → `/root/.dws`：profiles / identity / token.json / app.json
- 注入 `DWS_DISABLE_KEYCHAIN=1`（Linux 容器无 macOS Keychain，强制 file-DEK 模式）
- 容器内以 root 跑：宿主凭证文件 `600` 属 uid 501，root 不受权限位限制；换非 root 用户要处理属主映射
- macOS 路径含空格（`Application Support`）：`docker run` 参数走 ProcessBuilder 数组不经 shell，无需额外引号

**为什么不用容器内 device-flow**：
- 容器由 harness 控制在 sync chat 调用结束就 release，生命周期 ≤ 5min
- device-flow 通常要 5-10min 等用户扫码
- 容器销毁时 dws 进程被 SIGKILL，device-flow 永远走不完
- 因此 device-flow **只在 host 端跑**（已有 DwsAuthService / DwsAuthController 完整实现），前端通过 SSE 订阅 `/api/dws/auth/stream/{userId}`

**安全边界**：挂载 = 登录态持续暴露给容器内所有进程（refresh token 可被读走），**只用于可信沙箱**。宿主与容器共享同一份登录态：refresh token 不互踢，但避免两边同时高频调用写竞争。宿主侧 export 凭证记得带 `DWS_DISABLE_KEYCHAIN=1 dws auth export ...`（file-DEK 模式已就位）。

**容器内 dws 报错排查**：
```bash
# 手动起一个同款挂载的容器验证登录态
docker run -it --rm \
  -v "$HOME/Library/Application Support/dws-cli:/root/.local/share/dws-cli" \
  -v "$HOME/.dws:/root/.dws" \
  -e DWS_DISABLE_KEYCHAIN=1 \
  untitled-sandbox:latest bash
# 预期 authenticated: true；两个租户 profile 都在
dws auth status --format json
dws profile list --format json
dws contact user get-self --format json
# keychain/解密类报错 → 确认 DWS_DISABLE_KEYCHAIN=1 已注入 + dek 与 auth-token*.enc 同目录
```

## 沙箱文件浏览器（per-Agent, 只读 + 显式唤醒）

ChatView 头部「文件」按钮 → 右侧抽屉（`SandboxFileDrawer.vue`）：左侧 `el-tree` 懒加载浏览 `~/.workspace/<agentId>/<userId>` 在容器内的 `/workspace` 投影，右侧按文件类型分发预览（图片 el-image / Markdown marked / 代码 highlight.js + 通用 common bundle / 二进制→下载）。

**核心难点**：框架的 `SandboxBackedFilesystem.requireSandbox(rc)` 仅在 agent 调用上下文里绑定 `SandboxAcquireResult`，外部 controller 直调会抛 `No active sandbox`。本仓库 `SandboxFileService` 走的是 **PinnedSandboxFilesystem + 自行 acquire**：

1. `AgentRegistry` buildAgent 时把 `DockerFilesystemSpec` + 宿主 workspace 根存入 `sandboxHandles: ConcurrentHashMap<agentId, SandboxHandle>`（删除/重建 agent 同步清理）
2. Service 每请求：
   - 用 `fsSpec.toSandboxContext(hostWorkspaceRoot)`（public final）拿到含 client / snapshot / workspaceSpec 的 `SandboxContext`
   - 自建 `SandboxManager(client, SessionSandboxStateStore, agentId, SandboxExecutionGuard.noop())` + `RuntimeContext(userId, sessionId)`
   - **`manager.acquire(ctx, rc)`**：USER scope key = `sandbox/user/<agentId>/<userId>`（state 落盘基底） → Priority 3 从 `~/.agentscope/state/<agentId>/__anon__/<base64url(slotSid)>/_sandbox_state.json` resume 同一容器；缺失时 Priority 4 新建
   - `result.getSandbox().start()` → `doEnsureContainerRunning()` 自愈（stopped 容器 docker start / 已删容器 docker run + 从 `docker-snapshots/*.tar` 还原 workspace）
   - `manager.persistState(result, ctx, rc)` 幂等覆盖
   - `new PinnedSandboxFilesystem(sandbox)` + 构造 `RuntimeContext` 携带 `SandboxAcquireResult` → 框架的 `ls/read/downloadFiles` 全套命令可用（路径校验 / sed 分页 / base64 二进制全部由 `BaseSandboxFilesystem` 处理）
3. **不调用 `manager.release()`**——避免 stop() 触发 workspace 全量 tar 快照（成本高且对只读场景无必要）。容器由 chat 路径的 release 自然接管，5 分钟空闲后 `SandboxKeepAliveManager.scheduleShutdown` 延迟销毁

**状态字段**（`status` 端点只读 state，不 acquire、不 docker I/O）：

- state 不存在 → `running=false` → 前端显示「启动沙箱」按钮
- state 存在 → `running=true`（实际容器是否在跑由 chat / wake 路径保证；status 是 hint，非权威）

**`wake` 端点**：state 缺失时显式 Priority 4 创建（首次启动 5-30s），state 存在时 Priority 3 resume + start 自愈。完成后不 release，让 chat 路径或 keepalive 自然回收。

**路径安全**：所有访问限 `workspaceRoot`（默认 `/workspace`）内，三重校验：
1. `userId` 走 `^[a-zA-Z0-9_-]{1,64}$`（Spring 自动 binding 校验由 controller 层加 `Pattern`）
2. `AbstractFilesystem.validatePath(path)` 拒 `..`
3. `normalize().startsWith(workspaceRoot)` 兜底

**内容预览策略**：
- 文本/代码：返回 `utf-8`，前端按扩展名分发（.md → marked，其它 → highlight.js）。单次默认 2000 行（与框架 `BaseSandboxFilesystem.read` 默认一致），可传 `offset` 翻页；超过 2MB 标 `tooLarge=true` 提示下载
- 二进制：content 端点返回 `base64`（框架内部 cat | base64）；raw 端点直接走 docker bytes + 按扩展名 MIME + `Content-Disposition: inline|attachment`
- **HTML / SVG 预览**：走 `<iframe sandbox="" referrerpolicy="no-referrer">` 渲染（`classify()` 分发）
  - `sandbox=""` 阻止脚本执行、表单提交、top-level navigation、外部资源加载（CDN link/script 拦截）
  - HTML 内联 `<script>alert(1)</script>` 不执行
  - 外部 CSS/JS 不加载；预览仅展示 HTML 结构与文本内容
  - 文件类型分类器 `frontend/src/utils/fileKind.ts`（`classify()` 纯函数 + vitest 单测）

**文件大小防护**：raw 单端点不走 size 校验（沿用 `downloadFiles` 行为）；UI 上「下载」链接始终可用。

**`defaultStateDir` 复刻**：`io.agentscope.harness.agent.HarnessAgent#defaultStateDir` 是包私有 static，Service 在 `defaultStateDir(agentId)` 复刻其逻辑：优先 `agentscope.state.home` 系统属性 → `~/.agentscope/state/<agentId>`。保持与 chat 路径用同一份 `JsonFileAgentStateStore` 目录。

**前端注意点**：
- `buildSandboxRawUrl` 手动拼路径而非走 axios：图片 `<el-image :src>` 与 `<a download>` 需要纯字符串 URL；axios 请求拦截器在 Electron 下会改写 URL，但直接 window.open 或 `<a>` 不经 axios
- `chatStore.userId` 默认 `'anonymous'`，初次进入 drawer 即可浏览（无需先对话生成 sessionId）
- **userId / sessionId 兜底对齐 chat 路径**：`SandboxFileService` 对空白 `userId` → `"anonymous"`、空白 `sessionId` → `"default"`，与 `AgentService.ctx()` 完全一致。否则空 userId 会让 `SandboxIsolationKey` USER scope 降级到 SESSION（用 sessionId 作 key value），与对话路径 USER+anonymous 再次错位。

**GitHub 参考借鉴**：OpenHands `files-tab.tsx`（rich/plain 切换 + 文件分发器）、Daytona PR#4530（虚拟化树 + 大文件保护）、dsh-agfs（路径安全 `strictRoot` 模式）、dsh-preview-ui（HTML iframe 沙箱 + `X-Content-Type-Options: nosniff`）。

## AgentScope 框架坑（2.0.3 release 版）

- **ToolsConfig 没有 `strictAllow`/`setDefaultToolsEnabled`**（仅 SNAPSHOT 版有）。Allow 白名单是核心机制，启用即生效。
- **SkillFilter.only/except/all/none** 在 release 版都可用，配合 `builder.skillFilter(...)` 注入。
- **`SkillsConfig.builder.projectGlobalSkillsDir(Path)`** 注册全局 skill 仓库，配合 SkillFilter 实现按 agent 可见性过滤。
- **会话是懒创建**：stream/sync API 首次被调用即开始写 `<sessionId>.jsonl`，无需独立 create 接口。
- **`SkillUtil.createFromZip` 要求 zip 内单根目录**——macOS Finder 导出的 zip 含 `__MACOSX/` 噪音，`SkillService.createFromZip` 已在调用前 `stripAppleDoubleEntries` 清洗。
- **HarnessAgent.streamEvents(Msg, RuntimeContext)** 重载传 sessionId+userId；不要忘了 RuntimeContext，否则 session 不持久化。
- **GatewayBootstrap 强制至少 1 个 agent**——`DingTalkChannelRegistry` 采用两阶段构造：`initialize(seeds)` / `initializeWithStub()`，由 `AgentRegistry.loadFromPersistence()` 在加载完 spec 后调用。持久化列表为空时构造 OpenAI 占位 stub 作 main（URL 是 127.0.0.1:1，永远不会被路由）。
- **DingTalkChannel 在 `Channel.start()` 内异步建 WebSocket**——`DingTalkStreamClient` 自带指数退避重连，所以 PUT /api/agents/{id}/dingtalk 返回 200 不等于钉钉开放平台侧 stream 已连通。要验证连通性需看日志中的 access_token / stream 注册成功行。
- **HarnessAgent.Builder 不暴露 `knowledge()` / `ragMode()`**（这俩只在 `ReActAgent.Builder` 上有，且 `@Deprecated(forRemoval=true)` since 2.0.0）。本仓库用自实现的 `BailianKnowledgeTool`（`@Tool(name="retrieve_knowledge")`）走「`Toolkit.registerTool(Object)` → `HarnessAgent.getToolkit()`」路径，**完全脱离 v1 RAG API**。`AgentRegistry.buildAgent()` 在 `build()` 完成后往 `agent.getToolkit()` 注册 rag 工具，从而保留 harness 内置工具（filesystem/shell/memory/plan/skills）。
- **bailian SDK 体积**：`bailian20231229` 引入阿里云核心包 ~10MB，sidecar JAR 体积会增大；可接受。

## 关键命令（**npm workspaces：所有命令从仓库根目录运行**）

| 操作 | 命令 |
|---|---|
| 安装所有依赖 | `npm install` |
| 后端启动 | `mvn -q spring-boot:run`（后台：`nohup mvn -q spring-boot:run > /tmp/backend.log 2>&1 &`） |
| 后端编译 | `mvn -q compile` |
| 后端打包（生成可执行 JAR） | `mvn -q -DskipTests package` → `target/untitled-1.0-SNAPSHOT.jar` |
| 前端启动 | `npm run dev:frontend` |
| 前端类型检查 | `npm run type-check:frontend` |
| 前端构建 | `npm run build:frontend` |
| 桌面客户端启动 | `npm run build:desktop`（自动 prepare:frontend + prepare:backend + electron） |
| 桌面开发模式（HMR） | `npm run dev:desktop` |
| 桌面仅重打前端 | `npm run build:frontend` |
| 桌面仅重打后端 | `npm --workspace desktop run prepare:backend` |
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

## SSE 流式约定

- **`POST /api/chat/stream`** body=JSON `ChatRequest` → `text/event-stream` 响应（浏览器 `EventSource` 不支持 POST，前端用 `fetch` + 手动 SSE parser 解析）
- `event:thinking`           → 思考块 delta
- `event:message`            → 正文 delta
- `event:tool_start`         → `{"id","name"}` 工具调用开始
- `event:tool_delta`         → `{"id","delta"}` 工具入参增量（JSON 片段）
- `event:tool_end`           → `{"id"}` 工具入参结束
- `event:tool_result`        → `{"id","name","state","output"}` 工具执行完成
- `event:tool_result_delta`  → `{"id","name","delta"}` 工具执行输出增量（前端按 id 累积到 toolCall.output）
- `event:usage`              → `{"seq","time","inputTokens","outputTokens","cachedTokens","totalTokens","replyId","createsEntry"}` 本次 LLM 调用结束（每个 `ModelCallEndEvent` 触发一次；同一轮回复在 ReAct 工具循环下可能产生多条；`AgentScope.ChatUsage` 聚合后产出，不含 reasoning_tokens）
- `event:done`               → 流结束哨兵（前端据此关闭流）
- 后端 `StreamController.stream(...)` 返回 `Flux<ServerSentEvent<String>>`（concatMap 保证严格顺序）
- 同步 `POST /api/chat` 返回 `ChatResponse{reply, thinking, toolCalls[], usages[]}`
- 前端用 `ToolCallBlock` 组件展示（**默认折叠**，点击展开 arguments / output）
- 前端用 `UsageStats` 组件挂在每条 AI 回复**下方**（一行汇总：`用量：N次调用，输入 X 输出 Y 缓存 Z 缓存率 P%，总计 T，TIME`，不折叠不展开；多条 LLM 调用聚合展示）

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
├── chat-uploads/<agentId>/<userId>/<uuid>-<name>  ← 聊天附件（bind mount 到沙箱 /workspace/uploads/）
└── workspace/<agentId>/
    ├── <userId>/<name>/agents/<name>/sessions/<sid>.jsonl   ← AgentScope 会话流（gitignore）
    └── usage/<sid>.usage.jsonl                              ← LLM 用量 sidecar（每行一条 UsageDto JSON）
        usage/<sid>.attachments.jsonl                        ← 聊天附件 sidecar（每行一条 {ts, attachments[]} JSON）
```

**Usage sidecar**：
- 每个 LLM 调用结束（`ModelCallEndEvent`）在 `AgentService.UsageTracker.endCall` 写入一条 JSONL；同步路径同样记录但不落盘（chat 列表用）
- `SessionService.getHistory` 读取 sidecar 并按模型调用顺序挂回对应 ASSISTANT 消息：createsEntry=true 的记录与 ASSISTANT MessageEntry 1:1 分配；createsEntry=false（纯 tool_use、无文本）合并到上一条已分配的 ASSISTANT 消息（与 tool_use 的 parentId 回挂逻辑一致）
- `SessionService.deleteSession` 同步删除 `<sid>.usage.jsonl`
- 该文件**不是** AgentScope 框架产物，纯自管；不走 `.agentscope/agents.json` / Skill 落盘的 schema 版本管理

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

## 定时任务（org.example.task.*，Quartz 触发）

围绕「选已有数字人 + 任务提示词 + 必用 skill + cron 计划」展开；触发引擎 = Quartz（JDBC job store 集群就绪），执行体复用注册数字人 + 落独立会话 + 独立执行记录。

**结构**：
```
ScheduledTask          → ac_scheduled_task 表行
TaskRun                → ac_task_run 表行（每执行一次一行）
AcScheduledTaskMapper / AcTaskRunMapper → MyBatis @Mapper 接口（简单 CRUD 注解式 + 复杂查询 XML）
SkillsTypeHandler       → List<String> ⇄ JSON 串（ac_scheduled_task.skills 列）
LastRunSummary          → 聚合查询 DTO（GROUP BY task_id）
TaskSchedulerService   → Quartz 装配（CRUD ↔ Job/Trigger 同步 + nextFireTime + run-now）
TaskExecutionService   → 执行体，含私有静态嵌套类 `Fire implements Job`（@DisallowConcurrentExecution + 静态 Holder）
StartupReconciler      → ApplicationRunner 启动对齐
TaskController         → REST
TaskHolderBootstrap    → 注册 AgentRegistry 到 TaskSchedulerService 静态 holder（list 修饰 agentName 用）
```

**Quartz 关键设计**（参考官方 `QuartzAgentScheduler` 源码同款模式）：
- JobKey = (group="scheduled-tasks", name=taskId)；TriggerKey 同
- JobDataMap = `{taskId, triggerType}`（String 可序列化，JDBC store 安全）
- `JobBuilder.storeDurably(true)` → 停用任务保留 Job，`triggerJob` 仍可触发
- `pauseJob/resumeJob` 启停（官方同款）；`rescheduleJob` 改 cron；`deleteJob` 删除
- CronTrigger `withMisfireHandlingInstructionDoNothing()`（停机不补跑）；时区默认
- `@DisallowConcurrentExecution` 任务级单飞行（Quartz 任务级互斥；集群下 JDBC lock 同样生效）
- 应用层补充：`TaskExecutionService.running` ConcurrentHashMap 双保险 + run-now 预检 `getCurrentlyExecutingJobs()` 与 `isRunning()`

**REST 全集**：
```
GET    /api/tasks                    列表（含 lastRunAt/nextRunAt/enabled/agentName）
POST   /api/tasks                    创建（注册 Quartz Job+Trigger）
PUT    /api/tasks/{id}               更新（cron 变更走 rescheduleJob）
DELETE /api/tasks/{id}               删除（deleteJob + 删 ac_task_run 行）
POST   /api/tasks/{id}/run           立即执行 → 202 {runId}；执行中 → 409
GET    /api/tasks/{id}/runs          执行记录倒序（limit 默认 100）
```

**Skill 强制机制**：任务选 skill = 提示词点名（官方唯一机制）。执行时拼 `【定时任务「name」自动执行】本次任务必须先加载并严格遵循以下技能：X、Y（使用 load_skill_through_path 加载）。` + 原 prompt。**不注入 SkillFilter 到 RuntimeContext**（架构文档明确 RuntimeContext = 身份/元数据，不改行为配置；未文档化的能力会被版本升级破坏）。任务表单的 skill 选择器数据源 = 所选数字人已授权 skills（agent.skills 非空取白名单，空则全量），避免点名了但白名单过滤掉。

**执行流程**：
1. 单飞行守卫（应用层 + Quartz 双保险）
2. 写 run 记录 `RUNNING` + `sessionId = task-<ts36>-<rand>`（符合 `^[a-zA-Z0-9_-]{1,64}$`，与 SandboxFileService userId 兜底对齐）
3. 拼装 prompt 前缀（含 skill 点名）
4. `AgentService.reply(agentId, sessionId, "anonymous", prompt, null, timeout=15min)`（新增 Duration 重载）
5. 补写 Reply.usages 到 session usage sidecar（同步路径原本不写）
6. 更新 run：SUCCESS（replyPreview≤500 + tokens 聚合 + duration）/ FAILED / TIMEOUT
7. 列表层 `lastRunAt` 由 `ac_task_run` 聚合（MyBatis XML mapper）；`nextRunAt` 由 Quartz `Trigger.getNextFireTime()`

**会话 + UI 集成**：
- 任务会话 id 前缀 `task-`：`SessionService.listSessions` 过滤该前缀 → 不污染聊天侧栏；`ChatView ?agent=&session=` 深链直达（仅 GET 仍可达）
- 「执行记录」抽屉可一键跳转 `/chat?agent=&session=`，呈现完整对话
- 前端类型 `ScheduledTask` / `TaskRun`；store `stores/tasks.ts`；`TaskFormDialog`（cron 预设：每天/每周/每月/每小时 + 自定义 6 段 quartz cron）

**数据库 + 启动前置**：
- 数据源 `spring.datasource.url=jdbc:mysql://localhost:33061/agentscope?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=Asia/Shanghai`
- 用户名密码 root/root（开发环境 Docker `m-mysql` 容器，33061→3306）
- ORM：**MyBatis**（`mybatis-spring-boot-starter:4.1.0`，对齐 Spring Boot 4.1）
  - `mybatis.mapper-locations=classpath:mapper/*.xml`、`type-handlers-package=org.example.task.typehandler`、`map-underscore-to-camel-case=true`
  - 简单 CRUD 用 `@Select/@Insert/@Update/@Delete` 注解；聚合查询（`lastRunAtByTaskId`）和多语句（`trim`）放 `mapper/*.xml`
  - `ScheduledTask.skills` 是 `List<String>` ⇄ `VARCHAR`（JSON），由 `SkillsTypeHandler` 自动转换
- 库与表 schema 由 `src/main/resources/schema.sql` 自动建（`spring.sql.init.mode=always`）：
  - QRTZ_* 11 张表 + 索引（来自 quartz-2.5.2 的 `tables_mysql_innodb.sql`，去掉 DROP + commit，所有 INDEX 内联到 CREATE TABLE 内保证 MySQL 幂等）
  - **业务表带 `ac_` 前缀**（命名规约）：`ac_scheduled_task` + `ac_task_run`；schema.sql 顶部 `DROP TABLE IF EXISTS` 清理旧命名（`scheduled_task`/`task_run`）
- 前置检查清单：`docker exec m-mysql mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS agentscope DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"`（首次）
- ⚠️ 桌面 Electron sidecar 同样依赖该 MySQL 可达；分发时若需切换可在 application-local.yml 覆盖

**集群迁移路径**（字段迁移指南，代码零改）：
1. `spring.datasource.url` 改指向共享 MySQL
2. `spring.quartz.job-store-type: jdbc` 已是；保持
3. `spring.quartz.properties.org.quartz.jobStore.isClustered: true`（Quartz 行锁保证同一任务多节点只触发一次）
4. `spring.quartz.jdbc.initialize-schema: never`（集群初始化由手动跑 `tables_mysql_innodb.sql` 完成）
5. 全集群配套：会话（官方 MySQL/Redis/OSS State Store）、workspace（官方 remote filesystem）、钉钉 channel 单活——本模块就绪，独立项目

**Decision matrix / 不做的事**：
- **不引入 `agentscope-extensions-scheduler-quartz`**：schedule() 硬编码 AgentQuartzJob（无法注入 Job 体）；createAgent() private 现造 agent 无法绑定注册数字人；block().pop 丢弃结果无 sessionId/记录可挂
- **不向 RuntimeContext 注入 SkillFilter**：架构文档明确 RuntimeContext = 身份/元数据；未文档化能力升级易碎
- **不在前端用 MethodInvokingJobDetailFactoryBean 替代 Job 类**：MethodInvoker 不可序列化（javap 确认），JDBC store JobDataMap 落库会失败

## 上手检查清单（首次或重大改动后）

1. `mvn -q compile` 通过
2. `cd frontend && npx vue-tsc --noEmit` 通过
3. 后端启动：浏览器访问 `http://localhost:8080/swagger-ui.html` 应能列出所有 API
4. 前端启动：`http://localhost:5173/` 应能跳到 `/digital-humans`
5. 创建一个 Agent → 选模型发消息 → 看 SSE 流 → 检查 `.agentscope/workspace/<agentId>/.../sessions/*.jsonl` 是否落盘
6. 桌面客户端：`cd desktop && npm install && npm run start` → 弹出 Electron 窗口 → 侧栏后端状态显示「已连接」→ 三视图可正常操作
7. 定时任务：Docker `m-mysql` 容器在跑（端口 33061）→ `docker exec m-mysql mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS agentscope"`（首次）→ 后端启动后检查 `agentscope` 库包含 QRTZ_* + ac_scheduled_task + ac_task_run 表 → 创建任务（每分钟 cron）→ 观察触发 → run SUCCESS → 深链会话 → 立即执行 / 409 / 编辑 / 删除
8. **持久化验证**（定时任务 / Quartz JDBC 核心收益）：重启后端 → 触发器仍在、下次执行时间正确（看 log 中 `Reconciled task ...`）
