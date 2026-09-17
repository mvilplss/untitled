package org.example.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "从磁盘 jsonl 读出的历史消息")
public class ChatHistoryMessage {

    @Schema(description = "USER 或 ASSISTANT", example = "USER", allowableValues = {"USER", "ASSISTANT"})
    private String role;

    @Schema(description = "正文（已剥离 <think>/<reasoning> 块）",
            example = "我叫张三")
    private String content;

    @Schema(description = "推理过程（可能为空）", example = "用户问我叫什么")
    private String thinking;

    @Schema(description = "秒级 epoch", example = "1737000000")
    private long timestamp;

    @Schema(description = "本次回复中发生的工具调用列表（按发生顺序）；可空")
    private List<ToolCallDto> toolCalls = new ArrayList<>();

    public ChatHistoryMessage() {}

    public ChatHistoryMessage(String role, String content, String thinking, long timestamp) {
        this.role = role;
        this.content = content;
        this.thinking = thinking;
        this.timestamp = timestamp;
    }

    public ChatHistoryMessage(String role, String content, String thinking, long timestamp,
                              List<ToolCallDto> toolCalls) {
        this.role = role;
        this.content = content;
        this.thinking = thinking;
        this.timestamp = timestamp;
        this.toolCalls = toolCalls == null ? new ArrayList<>() : toolCalls;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public List<ToolCallDto> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallDto> toolCalls) { this.toolCalls = toolCalls; }
}