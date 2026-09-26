---
name: dws-auth
description: 在沙箱容器内完成 dws (dingtalk-workspace-cli) 的 device-flow 登录。当用户需要在容器内登录 dws、绑定钉钉账号、或检查 dws 登录状态时使用。凭证独立存储在容器 workspace 中，由快照持久化。
---

# dws 容器内登录（device-flow）

在沙箱容器内完成 dws 的 device-flow OAuth 授权。凭证存储在容器 workspace 中，容器销毁重建后由快照自动恢复。

## 环境变量（容器启动时已注入）

| 变量 | 值 | 说明 |
|---|---|---|
| `DWS_CONFIG_DIR` | `/workspace` | dws 配置文件（app.json、profiles.json） |
| `DWS_KEYCHAIN_DIR` | `/workspace/.dws/keychain` | 加密凭证（dek + auth-token*.enc） |
| `DWS_DISABLE_KEYCHAIN` | `1` | 强制 file-DEK 模式（Linux 容器无 macOS Keychain） |

## 登录流程

### 第 1 步：准备目录

```bash
mkdir -p /workspace/.dws/keychain
```

### 第 2 步：发起 device-flow

```bash
nohup dws auth login --device > /tmp/dws-login.log 2>&1 &
```

### 第 3 步：提取 userCode 和扫码链接

```bash
sleep 3 && cat /tmp/dws-login.log
```

从输出中提取：
- **userCode**（`authorization code:` 后面的字符串）
- **verificationUriComplete**（`Or open the following link:` 后面的 URL）

### 第 4 步：向用户展示

将 userCode 和扫码链接展示给用户，请用户在浏览器中打开链接并输入 userCode 完成授权。

### 第 5 步：轮询登录状态

```bash
dws auth status --format json
```

每 5 秒执行一次，检查返回的 `authenticated` 字段：
- `true` → 登录成功，进入第 6 步
- `false` → 继续轮询
- 超过 10 分钟仍为 `false` → 提示用户重新发起登录

### 第 6 步：确认登录成功

```bash
dws auth status --format json
dws profile list --format json
```

确认 `authenticated: true` 且 profile 列表正常。告知用户登录完成。

## 登录状态检查

用户询问登录状态时，执行：

```bash
dws auth status --format json
```

- `authenticated: true` → 已登录
- `authenticated: false` 或退出码非 0 → 未登录，提示用户走登录流程

## 登出

```bash
dws auth reset --format json
rm -rf /workspace/.dws/keychain/*
```

## 注意事项

- 凭证写入 `/workspace/`，由快照持久化。容器销毁重建后登录态自动恢复。
- token 自动刷新会写回 `/workspace/.dws/keychain/`，下次快照会持久化刷新后的 token。
- 如果 `/workspace/app.json` 或 `/workspace/profiles.json` 不存在，说明尚未登录。
- device-flow 通常需要 5-10 分钟等用户扫码，请耐心轮询。
