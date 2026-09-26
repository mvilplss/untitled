package org.example.task;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务定义。一行对应一条 {@code scheduled_task} 表记录 + 一个 Quartz Job+Trigger。
 *
 * <p>id 必须满足 {@code ^[a-zA-Z0-9_-]{1,64}$}（Quartz JobKey 限定 + DB 列宽）。
 * cron 是 Quartz 6 段语法（秒 分 时 日 月 周），用 {@code ?} 处理 DOM/DOW。
 */
public class ScheduledTask {

    private String id;
    private String name;
    private String agentId;
    private String prompt;
    /** JSON 数组形式存储的 skill 名清单；null/空 = 沿用 AgentSpec 的 skill 配置 */
    private List<String> skills;
    private String cron;
    private boolean enabled;
    private long createdAt;
    private long updatedAt;

    /** 仅查询响应携带（来自 task_run 聚合），不持久化在 scheduled_task 行 */
    private Long lastRunAt;
    /** 来自 Quartz Trigger.getNextFireTime()，暂停时为 null */
    private Long nextRunAt;
    /** 列表展示用，便于前端直接显示 AgentSpec.name（避免一次额外请求） */
    private String agentName;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) { this.skills = skills; }

    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public Long getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Long lastRunAt) { this.lastRunAt = lastRunAt; }

    public Long getNextRunAt() { return nextRunAt; }
    public void setNextRunAt(Long nextRunAt) { this.nextRunAt = nextRunAt; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public static long now() { return Instant.now().toEpochMilli(); }
}