# Untitled Agent Console

动态多 Agent 控制台前端，对接同仓库 Spring Boot 后端（端口 8080）。

## 技术栈

| 类别 | 选型 |
|---|---|
| 构建 | Vite 5 |
| 语言 | TypeScript 5 |
| 框架 | Vue 3 (Composition API + `<script setup>`) |
| UI 组件库 | Element Plus 2 |
| 状态管理 | Pinia 2 |
| 路由 | Vue Router 4 |
| HTTP | Axios 1 |
| SSE | 原生 EventSource |
| Markdown | marked |

## 功能

### 1. Agent 管理 (`/agents`)
- 列出全部已注册 Agent（按 ID 升序）
- 新建 Agent（id / name / sysPrompt / modelName）
- 删除 Agent（带二次确认）

### 2. 对话 (`/chat`)
- 选择 Agent + 自定义 sessionId / userId
- **同步模式**：阻塞等待完整回复
- **流式模式**：按 token 实时渲染（EventSource 订阅 SSE）
- 同 sessionId 自动保留多轮上下文（由 AgentScope 持久化 session）
- Markdown 渲染（marked）
- 消息按时间正序展示，用户消息右侧、Agent 消息左侧
- 复制消息 / 清空对话 / 停止生成

## 启动

```bash
cd frontend
npm install
npm run dev
```

默认监听 `http://localhost:5173`，已配置 Vite proxy：
- `/api/*` → `http://localhost:8080/*`（后端）

可通过 `.env` 覆盖：
```
VITE_BACKEND_URL=http://your-backend:8080
```

## 构建生产

```bash
npm run build       # 产物在 dist/
npm run preview     # 本地预览构建产物
```

## 后端依赖

启动前确保后端已运行：
```bash
# 在项目根目录
mvn spring-boot:run
```

Swagger 文档：`http://localhost:8080/swagger-ui.html`
OpenAPI JSON：`http://localhost:8080/v3/api-docs`