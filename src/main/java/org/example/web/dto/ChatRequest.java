package org.example.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "同步对话请求体")
public class ChatRequest {

    @Schema(description = "目标 Agent ID，必须已通过 POST /api/agents 创建",
            example = "coder",
            requiredMode = Schema.RequiredMode.REQUIRED,
            pattern = "^[a-zA-Z0-9_-]{1,32}$")
    @NotBlank(message = "agentId 不能为空，请先调用 POST /api/agents 创建")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{1,32}$", message = "agentId 必须匹配 ^[a-zA-Z0-9_-]{1,32}$")
    private String agentId;

    @Schema(description = "会话 ID；相同 sessionId 复用历史上下文，默认 default",
            example = "default",
            defaultValue = "default")
    private String sessionId = "default";

    @Schema(description = "用户标识，默认 anonymous",
            example = "alice",
            defaultValue = "anonymous")
    private String userId = "anonymous";

    @Schema(description = "用户消息内容",
            example = "用一句话解释 volatile",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "message 不能为空")
    private String message;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}