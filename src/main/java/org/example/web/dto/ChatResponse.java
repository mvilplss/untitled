package org.example.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "同步对话响应体")
public class ChatResponse {

    @Schema(description = "Agent 回复正文（已剥离 <think>/<reasoning> 块）",
            example = "volatile 是 Java 中一种轻量级的同步机制...")
    private String reply;

    @Schema(description = "推理过程（<think>/<reasoning> 内容），可空；前端默认折叠展示",
            example = "用户问的是Java volatile 关键字...")
    private String thinking;

    @Schema(description = "本次回复中发生的工具调用列表，按发生顺序；可空")
    private List<ToolCallDto> toolCalls = new ArrayList<>();

    public ChatResponse() {}

    public ChatResponse(String reply) {
        this.reply = reply;
    }

    public ChatResponse(String reply, String thinking) {
        this.reply = reply;
        this.thinking = thinking;
    }

    public ChatResponse(String reply, String thinking, List<ToolCallDto> toolCalls) {
        this.reply = reply;
        this.thinking = thinking;
        this.toolCalls = toolCalls == null ? new ArrayList<>() : toolCalls;
    }

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public String getThinking() { return thinking; }
    public void setThinking(String thinking) { this.thinking = thinking; }

    public List<ToolCallDto> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallDto> toolCalls) { this.toolCalls = toolCalls; }
}