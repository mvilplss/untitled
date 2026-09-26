package org.example.bot;

/** 状态摘要 DTO。供前端展示与排错使用。 */
public record DingTalkBotHealth(
        String agentId,
        boolean enabled,
        String status,
        String lastError
) {}
