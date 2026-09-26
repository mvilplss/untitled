package org.example.task;

import org.example.task.mapper.AcScheduledTaskMapper;
import org.example.task.mapper.AcTaskRunMapper;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TimeZone;

/**
 * Quartz 装配层：把 scheduled_task 表的行 ↔ Quartz Job/Trigger 同步。
 *
 * <p>Quartz Job 与 Trigger 的设计选择（参考官方 QuartzAgentScheduler 源码同款模式）：
 * <ul>
 *   <li>JobKey = (group="scheduled-tasks", name=taskId) —— TriggerKey 同</li>
 *   <li>JobDataMap = {taskId, triggerType}（String 可序列化，JDBC job store 安全）</li>
 *   <li>JobDetail durable=true —— 停用任务（删 trigger）后保留 Job，「立即执行」仍可 triggerJob</li>
 *   <li>暂停用 scheduler.pauseJob（不是 pauseTrigger）—— 官方 pause 语义一致</li>
 *   <li>Misfire = DoNothing —— 停机期间错过的不补跑</li>
 * </ul>
 */
@Service
public class TaskSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(TaskSchedulerService.class);

    public static final String JOB_GROUP = "scheduled-tasks";

    private final Scheduler scheduler;
    private final AcScheduledTaskMapper taskMapper;
    private final AcTaskRunMapper runMapper;

    public TaskSchedulerService(Scheduler scheduler,
                                AcScheduledTaskMapper taskMapper,
                                AcTaskRunMapper runMapper) {
        this.scheduler = scheduler;
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    // ============ CRUD ↔ Quartz 同步 ============

    /** 创建任务：写库 + 注册 Quartz Job/Trigger。 */
    public ScheduledTask create(ScheduledTask t) {
        validateCron(t.getCron());
        validateId(t.getId());
        if (taskMapper.findById(t.getId()) != null) {
            throw new IllegalStateException("task '" + t.getId() + "' already exists");
        }
        long now = ScheduledTask.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        taskMapper.insert(t);
        scheduleInQuartz(t);
        log.info("Task created: id={}, cron={}, enabled={}", t.getId(), t.getCron(), t.isEnabled());
        return t;
    }

    /** 更新任务：同步 Quartz（cron 变 → rescheduleJob；enabled 变 → pause/resume；其他仅写库）。 */
    public ScheduledTask update(String id, ScheduledTask patch) {
        ScheduledTask existing = Optional.ofNullable(taskMapper.findById(id))
                .orElseThrow(() -> new NoSuchElementException("task '" + id + "' not found"));
        validateCron(patch.getCron());
        if (!id.equals(patch.getId())) {
            throw new IllegalArgumentException("path id '" + id + "' 与 body id '" + patch.getId() + "' 不一致");
        }
        // 保留 createdAt，更新 updatedAt
        patch.setCreatedAt(existing.getCreatedAt());
        patch.setUpdatedAt(ScheduledTask.now());
        taskMapper.update(patch);

        // 同步 Quartz
        try {
            ensureJobDetail(id);
            boolean cronChanged = !existing.getCron().equals(patch.getCron());
            if (cronChanged) {
                rescheduleInQuartz(patch);
            }
            if (patch.isEnabled()) {
                resumeInQuartz(id);
            } else {
                pauseInQuartz(id);
            }
        } catch (SchedulerException e) {
            log.error("Failed to sync Quartz for task {}", id, e);
            throw new RuntimeException("同步 Quartz 失败：" + e.getMessage(), e);
        }
        log.info("Task updated: id={}, cronChanged={}, enabled={}", id, cronChanged(patch, existing), patch.isEnabled());
        return patch;
    }

    public void delete(String id) {
        Optional.ofNullable(taskMapper.findById(id))
                .orElseThrow(() -> new NoSuchElementException("task '" + id + "' not found"));
        try {
            scheduler.deleteJob(jobKey(id));
        } catch (SchedulerException e) {
            log.warn("Quartz deleteJob failed for {}: {}", id, e.getMessage());
        }
        runMapper.deleteByTaskId(id);
        taskMapper.delete(id);
        log.info("Task deleted: id={}", id);
    }

    public ScheduledTask getById(String id) {
        ScheduledTask t = Optional.ofNullable(taskMapper.findById(id))
                .orElseThrow(() -> new NoSuchElementException("task '" + id + "' not found"));
        // 与 list 一致：补 lastRunAt / nextRunAt / agentName
        Map<String, Long> lastRuns = lastRunAtMap();
        t.setLastRunAt(lastRuns.get(t.getId()));
        try {
            Trigger trigger = scheduler.getTrigger(triggerKey(t.getId()));
            Date nft = trigger == null ? null : trigger.getNextFireTime();
            t.setNextRunAt(nft == null ? null : nft.toInstant().toEpochMilli());
        } catch (SchedulerException e) {
            t.setNextRunAt(null);
        }
        try {
            t.setAgentName(decorateAgentName(t.getAgentId()));
        } catch (Exception e) {
            t.setAgentName(null);
        }
        return t;
    }

    public List<ScheduledTask> list() {
        return decorateForList(taskMapper.findAll());
    }

    /** 把 lastRunAt / nextRunAt / agentName 填进去（合并查询开销）。 */
    public List<ScheduledTask> decorateForList(List<ScheduledTask> raw) {
        Map<String, Long> lastRuns = lastRunAtMap();
        for (ScheduledTask t : raw) {
            t.setLastRunAt(lastRuns.get(t.getId()));
            try {
                Trigger trigger = scheduler.getTrigger(triggerKey(t.getId()));
                Date nft = trigger == null ? null : trigger.getNextFireTime();
                t.setNextRunAt(nft == null ? null : nft.toInstant().toEpochMilli());
            } catch (SchedulerException e) {
                t.setNextRunAt(null);
            }
            try {
                t.setAgentName(decorateAgentName(t.getAgentId()));
            } catch (Exception e) {
                t.setAgentName(null);
            }
        }
        return raw;
    }

    /** 聚合：每个 task 的最近一次 run 起始时间。Mapper 返回 {@code List<LastRunSummary>}，此处转 Map。 */
    private Map<String, Long> lastRunAtMap() {
        Map<String, Long> out = new HashMap<>();
        for (LastRunSummary s : taskMapper.lastRunAtByTaskId()) {
            out.put(s.getTaskId(), s.getLastRunAt());
        }
        return out;
    }

    private String decorateAgentName(String agentId) {
        // 通过 registry 拿 name
        try {
            org.example.agent.AgentRegistry registry = SpringContextHolder.getAgentRegistry();
            return registry.findById(agentId).map(s -> s.getName()).orElse(agentId);
        } catch (Exception e) {
            return null;
        }
    }

    // ============ 立即执行 ============

    /**
     * 立即触发执行。返回 runId。
     * 前置检查同 JobKey 是否在 Quartz 中正在运行；并发时返回 null 给上层 409。
     */
    public String runNow(String taskId) {
        ScheduledTask task = Optional.ofNullable(taskMapper.findById(taskId))
                .orElseThrow(() -> new NoSuchElementException("task '" + taskId + "' not found"));
        // 前置 409 检查（quartz 层面）
        try {
            for (org.quartz.JobExecutionContext jec : scheduler.getCurrentlyExecutingJobs()) {
                if (jec.getJobDetail().getKey().equals(jobKey(taskId))) {
                    throw new IllegalStateException("任务正在执行中，请稍后再试");
                }
            }
        } catch (SchedulerException e) {
            log.warn("getCurrentlyExecutingJobs failed: {}", e.getMessage());
        }
        // 应用层单飞行（手动 vs 手动 / 手动 vs 定时）
        if (TaskExecutionService.Holder.get() != null
                && TaskExecutionService.Holder.get().isRunning(taskId)) {
            throw new IllegalStateException("任务正在执行中，请稍后再试");
        }

        // 确保 JobDetail 存在（停用任务的 JobDetail 仍保留，但保险起见兜底 ensureJobDetail）
        try {
            ensureJobDetail(taskId);
        } catch (SchedulerException e) {
            throw new RuntimeException("准备 JobDetail 失败：" + e.getMessage(), e);
        }

        String runId = "r-" + Long.toString(System.currentTimeMillis(), 36) + "-" + randomSuffix();

        // 先写一条 QUEUED 记录，让前端立刻能看到 runId
        TaskRun queued = new TaskRun();
        queued.setRunId(runId);
        queued.setTaskId(taskId);
        queued.setTriggerType(TaskRun.TRIGGER_MANUAL);
        queued.setStatus(TaskRun.STATUS_QUEUED);
        queued.setStartedAt(Instant.now().toEpochMilli());
        runMapper.insert(queued);

        // 提交异步执行（TaskExecutionService 直接调用，绕开 Quartz；不走 triggerJob 的原因：
        // 异步池与 Quartz 线程池独立，failure 不会污染 Quartz 状态）
        try {
            TaskExecutionService exec = TaskExecutionService.Holder.get();
            if (exec == null) {
                log.error("TaskExecutionService.Holder is null; bean not installed");
                throw new IllegalStateException("TaskExecutionService 未就绪");
            }
            exec.execute(taskId, TaskRun.TRIGGER_MANUAL, runId);
        } catch (Exception ex) {
            log.error("Manual run dispatch failed: taskId={}, runId={}", taskId, runId, ex);
            throw new RuntimeException("提交执行失败：" + ex.getMessage(), ex);
        }

        log.info("Manual run dispatched: taskId={}, runId={}", taskId, runId);
        return runId;
    }

    public List<TaskRun> findRuns(String taskId, int limit) {
        return runMapper.findByTaskId(taskId, limit);
    }

    // ============ 启动 reconcile ============

    /** 启动时调用：DB 中 enabled 的任务确保 Quartz 有对应 Job+Trigger；清理孤儿（可选）。 */
    public void reconcileOnStartup() {
        List<ScheduledTask> all = taskMapper.findAll();
        for (ScheduledTask t : all) {
            try {
                ensureJobDetail(t.getId());
                rescheduleInQuartz(t);
                if (t.isEnabled()) {
                    resumeInQuartz(t.getId());
                } else {
                    pauseInQuartz(t.getId());
                }
                log.info("Reconciled task {} (enabled={})", t.getId(), t.isEnabled());
            } catch (Exception e) {
                log.error("Failed to reconcile task {}: {}", t.getId(), e.getMessage());
            }
        }
    }

    // ============ Quartz 底层辅助 ============

    private void scheduleInQuartz(ScheduledTask t) {
        try {
            JobDetail jd = jobDetail(t.getId());
            Trigger tr = cronTrigger(t);
            scheduler.scheduleJob(jd, tr);
            if (!t.isEnabled()) {
                scheduler.pauseJob(jd.getKey());
            }
        } catch (SchedulerException e) {
            throw new RuntimeException("scheduleJob failed: " + e.getMessage(), e);
        }
    }

    private void rescheduleInQuartz(ScheduledTask t) throws SchedulerException {
        TriggerBuilder<?> builder;
        if (scheduler.checkExists(triggerKey(t.getId()))) {
            builder = scheduler.getTrigger(triggerKey(t.getId())).getTriggerBuilder();
        } else {
            builder = TriggerBuilder.newTrigger();
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        CronTrigger fresh = cronTrigger(t, (TriggerBuilder) builder);
        scheduler.rescheduleJob(triggerKey(t.getId()), fresh);
    }

    private void pauseInQuartz(String id) throws SchedulerException {
        JobKey jk = jobKey(id);
        if (scheduler.checkExists(jk)) scheduler.pauseJob(jk);
    }

    private void resumeInQuartz(String id) throws SchedulerException {
        JobKey jk = jobKey(id);
        if (scheduler.checkExists(jk)) scheduler.resumeJob(jk);
    }

    private void ensureJobDetail(String taskId) throws SchedulerException {
        JobKey jk = jobKey(taskId);
        if (scheduler.checkExists(jk)) return;
        // JobDetail 不存在时新建（durable + JobDataMap 引用模式）
        scheduler.addJob(jobDetail(taskId), true);
    }

    private JobDetail jobDetail(String taskId) {
        return JobBuilder.newJob(TaskExecutionService.Fire.class)
                .withIdentity(taskId, JOB_GROUP)
                .storeDurably()
                .usingJobData(TaskExecutionService.DATA_TASK_ID, taskId)
                .usingJobData(TaskExecutionService.DATA_TRIGGER_TYPE, TaskRun.TRIGGER_SCHEDULED)
                .build();
    }

    private CronTrigger cronTrigger(ScheduledTask t) {
        return cronTrigger(t, TriggerBuilder.newTrigger());
    }

    private CronTrigger cronTrigger(ScheduledTask t, TriggerBuilder<Trigger> builder) {
        return builder
                .withIdentity(t.getId(), JOB_GROUP)
                .forJob(t.getId(), JOB_GROUP)
                .withSchedule(CronScheduleBuilder.cronSchedule(t.getCron())
                        .inTimeZone(TimeZone.getTimeZone(ZoneId.systemDefault()))
                        .withMisfireHandlingInstructionDoNothing())
                .build();
    }

    public static JobKey jobKey(String taskId) {
        return JobKey.jobKey(taskId, JOB_GROUP);
    }

    public static TriggerKey triggerKey(String taskId) {
        return TriggerKey.triggerKey(taskId, JOB_GROUP);
    }

    // ============ 校验 ============

    private void validateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("cron 不能为空");
        }
        if (!org.quartz.CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("cron 表达式非法: " + cron);
        }
    }

    private void validateId(String id) {
        if (id == null || !id.matches("^[a-zA-Z0-9_-]{1,64}$")) {
            throw new IllegalArgumentException("id 必须匹配 ^[a-zA-Z0-9_-]{1,64}$");
        }
    }

    private boolean cronChanged(ScheduledTask patch, ScheduledTask existing) {
        return !patch.getCron().equals(existing.getCron());
    }

    private String randomSuffix() {
        return Long.toString((long) (Math.random() * 0xFFFFFFFL), 36);
    }

    /** 简单 Spring 上下文持有者：Quartz reconcile 时需要 AgentRegistry 注入名做 agentName 修饰。 */
    public static final class SpringContextHolder {
        private static volatile org.example.agent.AgentRegistry agentRegistry;

        public static void install(org.example.agent.AgentRegistry registry) {
            agentRegistry = registry;
        }

        public static org.example.agent.AgentRegistry getAgentRegistry() {
            return agentRegistry;
        }
    }

    /** 仅用于 startup reconcile 读取 nextFireTime 的辅助方法（返回 epoch 毫秒）。 */
    public Long nextFireAt(String taskId) {
        try {
            Trigger t = scheduler.getTrigger(triggerKey(taskId));
            if (t == null) return null;
            Date nft = t.getNextFireTime();
            return nft == null ? null : nft.toInstant().toEpochMilli();
        } catch (SchedulerException e) {
            return null;
        }
    }
}