# 沙盒容器镜像：Node.js 22 + 真实 dws (dingtalk-workspace-cli) + Python 3 执行环境。
# 构建（默认 host 架构；Apple Silicon 下是 linux/arm64，npm 自动取平台原生 vendor 二进制）：
#   cd docker && docker build -t untitled-sandbox:latest -f sandbox.Dockerfile .
#
# 用途：每个 Agent 启动时 `docker run` 这个镜像，作为工具执行沙箱（跑 Python/Node/shell + dws）。
# 与 application.yml agent.sandbox.image=untitled-sandbox:latest 配套使用。
#
# dws 登录态：AgentRegistry 启动容器时 bind-mount 宿主凭证目录到容器 XDG 路径（rw，token 刷新需写回）：
#   ~/Library/Application Support/dws-cli -> /root/.local/share/dws-cli   (auth-token*.enc + dek)
#   ~/.dws                              -> /root/.dws                    (profiles / identity / token.json)
# 另注入 DWS_DISABLE_KEYCHAIN=1（Linux 容器无 macOS Keychain，强制 file-DEK 模式）。
#
# dws 版本必须与宿主对齐（宿主 dws version = 1.0.62），避免凭证格式不兼容。

FROM node:22-bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# 运行时依赖：Python 3（agent 跑脚本）+ curl/jq（通用）+ unzip（dws npm postinstall 解 skills 包必需）
# + dumb-init（干净收 SIGTERM）。优先官方源，失败 fallback 清华源（与旧版镜像一致的容错）。
RUN set -eux; \
    if ! apt-get update -o Acquire::Retries=2 -o Acquire::http::Timeout=10 2>&1 | grep -q 'Err:'; then \
        apt-get install -y --no-install-recommends \
            python3 \
            python3-pip \
            python3-venv \
            python3-dev \
            ca-certificates \
            curl \
            jq \
            unzip \
            tzdata \
            dumb-init; \
    else \
        echo "Falling back to TUNA mirror for apt..." && \
        sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources && \
        apt-get update && \
        apt-get install -y --no-install-recommends \
            python3 \
            python3-pip \
            python3-venv \
            python3-dev \
            ca-certificates \
            curl \
            jq \
            unzip \
            tzdata \
            dumb-init; \
    fi && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    ln -sf /usr/bin/python3 /usr/bin/python

# npm 走清华源
RUN npm config set registry https://registry.npmmirror.com

# 真实 dws：npm 包装 + 平台原生 vendor 二进制（不锁 --platform，随构建架构取对应二进制）。
# 版本 pin 与宿主对齐（宿主 dws version = 1.0.62）。
RUN npm install -g dingtalk-workspace-cli@1.0.62 && npm cache clean --force

WORKDIR /work

# harness sandbox 期望容器保持前台运行直到 docker stop；
# dumb-init 收 SIGTERM 让 PID 1 干净退出，避免被 docker stop --time=10 强杀。
ENTRYPOINT ["/usr/bin/dumb-init", "--"]
CMD ["sh", "-c", "trap 'exit 0' TERM; while :; do sleep 3600 & wait $!; done"]
