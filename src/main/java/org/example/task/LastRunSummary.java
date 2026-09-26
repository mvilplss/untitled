package org.example.task;

/**
 * ac_task_run 聚合查询结果（{@code GROUP BY task_id}），供 TaskSchedulerService 装饰列表时取 lastRunAt。
 * <p>由 {@code AcScheduledTaskMapper.lastRunAtByTaskId()} 返回 {@code List<LastRunSummary>}。
 */
public class LastRunSummary {

    private String taskId;
    private Long lastRunAt;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }

    public Long getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Long lastRunAt) { this.lastRunAt = lastRunAt; }
}