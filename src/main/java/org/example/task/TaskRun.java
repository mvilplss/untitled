package org.example.task;

/**
 * 单次任务执行记录。
 *
 * <p>triggerType：SCHEDULED（cron 触发）/ MANUAL（立即执行按钮）。
 * status：QUEUED → RUNNING → SUCCESS/FAILED/TIMEOUT/SKIPPED。
 */
public class TaskRun {

    private String runId;
    private String taskId;
    private String triggerType;
    private String status;
    private Long startedAt;
    private Long finishedAt;
    private Long durationMs;
    private String sessionId;
    private String replyPreview;
    private String error;
    private Integer tokensInput;
    private Integer tokensOutput;
    private Integer tokensCached;
    private Integer tokensTotal;

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMEOUT = "TIMEOUT";
    public static final String STATUS_SKIPPED = "SKIPPED";

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getStartedAt() { return startedAt; }
    public void setStartedAt(Long startedAt) { this.startedAt = startedAt; }

    public Long getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Long finishedAt) { this.finishedAt = finishedAt; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getReplyPreview() { return replyPreview; }
    public void setReplyPreview(String replyPreview) { this.replyPreview = replyPreview; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public Integer getTokensInput() { return tokensInput; }
    public void setTokensInput(Integer tokensInput) { this.tokensInput = tokensInput; }

    public Integer getTokensOutput() { return tokensOutput; }
    public void setTokensOutput(Integer tokensOutput) { this.tokensOutput = tokensOutput; }

    public Integer getTokensCached() { return tokensCached; }
    public void setTokensCached(Integer tokensCached) { this.tokensCached = tokensCached; }

    public Integer getTokensTotal() { return tokensTotal; }
    public void setTokensTotal(Integer tokensTotal) { this.tokensTotal = tokensTotal; }
}