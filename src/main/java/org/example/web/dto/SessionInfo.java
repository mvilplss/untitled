package org.example.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Agent 下某个 session 的元信息")
public class SessionInfo {

    @Schema(description = "会话 ID", example = "demo-session")
    private String sessionId;

    @Schema(description = "消息总数（USER + ASSISTANT）", example = "12")
    private int messageCount;

    @Schema(description = "最近活跃时间（秒级 epoch）", example = "1737000000")
    private long lastActive;

    @Schema(description = "侧栏展示标题。普通会话=首条 USER 消息前 9 个 codePoint；定时任务会话=任务名（从 prompt 前缀抽出）",
            example = "你好世界")
    private String preview;

    @Schema(description = "是否为定时任务会话（sessionId 以 task- 开头）", example = "false")
    private boolean isTask;

    public SessionInfo() {}

    public SessionInfo(String sessionId, int messageCount, long lastActive, String preview) {
        this(sessionId, messageCount, lastActive, preview, false);
    }

    public SessionInfo(String sessionId, int messageCount, long lastActive, String preview, boolean isTask) {
        this.sessionId = sessionId;
        this.messageCount = messageCount;
        this.lastActive = lastActive;
        this.preview = preview;
        this.isTask = isTask;
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    public long getLastActive() { return lastActive; }
    public void setLastActive(long lastActive) { this.lastActive = lastActive; }

    public String getPreview() { return preview; }
    public void setPreview(String preview) { this.preview = preview; }

    @JsonProperty("isTask")
    public boolean isTask() { return isTask; }
    public void setTask(boolean task) { isTask = task; }
}