package org.example.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "单次 LLM 调用的 token / 耗时统计。AgentScope ChatUsage 聚合后产出。会话期间的所有调用都会对应一条此记录。")
public class UsageDto {

    @Schema(description = "同次会话内的调用序号（0 开始），便于前端按顺序展示",
            example = "0")
    private int seq;

    @Schema(description = "本次 LLM 调用的耗时（秒）",
            example = "3.21")
    private double time;

    @Schema(description = "输入 token 数（prompt_tokens）", example = "2346")
    private int inputTokens;

    @Schema(description = "输出 token 数（completion_tokens，含 reasoning）", example = "524")
    private int outputTokens;

    @Schema(description = "命中缓存的输入 token 数（prompt_tokens_details.cached_tokens）", example = "128")
    private int cachedTokens;

    @Schema(description = "总 token 数（input + output）", example = "2870")
    private int totalTokens;

    @Schema(description = "AgentScope 内部 replyId，与流式事件 replyId 对应，便于排查",
            example = "06fbfc818846ddb29f4620d2fb0dd521")
    private String replyId;

    @Schema(description = "本次调用是否产生了带文本 / 思考的 assistant 消息条目（用于历史 jsonl 反查匹配）",
            example = "true")
    private boolean createsEntry;

    public UsageDto() {}

    public UsageDto(int seq, double time, int inputTokens, int outputTokens,
                    int cachedTokens, int totalTokens, String replyId, boolean createsEntry) {
        this.seq = seq;
        this.time = time;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cachedTokens = cachedTokens;
        this.totalTokens = totalTokens;
        this.replyId = replyId;
        this.createsEntry = createsEntry;
    }

    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }

    public double getTime() { return time; }
    public void setTime(double time) { this.time = time; }

    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

    public int getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(int cachedTokens) { this.cachedTokens = cachedTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public String getReplyId() { return replyId; }
    public void setReplyId(String replyId) { this.replyId = replyId; }

    public boolean isCreatesEntry() { return createsEntry; }
    public void setCreatesEntry(boolean createsEntry) { this.createsEntry = createsEntry; }
}