package org.example.task;

import org.example.agent.AgentRegistry;
import org.example.service.AgentService;
import org.example.service.SessionService;
import org.example.task.mapper.AcScheduledTaskMapper;
import org.example.task.mapper.AcTaskRunMapper;
import org.example.web.dto.UsageDto;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 定时任务执行体。Quartz {@link Fire} 触发或 REST 立即执行（直接调本 service）都汇入同一入口
 * {@link #execute(String, String, String)}，保证集群下唯一来源（防止一任务在多节点并行）。
 *
 * <p>单飞行保护：{@link #running} 集合（应用层）+ {@code @DisallowConcurrentExecution}
 * （Quartz 任务级，集群下 JDBC 锁）双保险。
 */
@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    /** 任务执行超时：与 AgentService blockLast(Duration) 配合。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);
    /** 单次执行预览最大保留字符数（避免 LONGTEXT 暴涨）。 */
    private static final int REPLY_PREVIEW_MAX = 500;
    /** 会话 ID 前缀（与 sandbox 浏览页 + listSessions 过滤约定一致）。 */
    public static final String SESSION_PREFIX = "task-";
    /** JobDataMap 字段名。 */
    public static final String DATA_TASK_ID = "taskId";
    public static final String DATA_TRIGGER_TYPE = "triggerType";
    public static final String DATA_RUN_ID = "runId";
    public static final String DATA_STARTED_AT = "startedAt";

    private final AgentService agentService;
    private final AgentRegistry registry;
    private final AcScheduledTaskMapper taskMapper;
    private final AcTaskRunMapper runMapper;
    private final SessionService sessionService;
    private final ExecutorService asyncPool;

    /** 任务执行单飞行：taskId → 正在执行的 runId。 */
    private final ConcurrentHashMap<String, String> running = new ConcurrentHashMap<>();

    public TaskExecutionService(AgentService agentService,
                                AgentRegistry registry,
                                AcScheduledTaskMapper taskMapper,
                                AcTaskRunMapper runMapper,
                                SessionService sessionService) {
        this.agentService = agentService;
        this.registry = registry;
        this.taskMapper = taskMapper;
        this.runMapper = runMapper;
        this.sessionService = sessionService;
        // 小并发：长 agent 运行；2 线程足够本地单节点
        this.asyncPool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "task-exec-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        // 注册到静态 holder，供 @DisallowConcurrentExecution 包装的 Fire Job 反射调用
        Holder.install(this);
    }

    /**
     * 执行一次任务。会写 task_run 全过程状态（QUEUED → RUNNING → 终态）。
     * 由立即执行触发（trigger=MANUAL）或 Quartz Fire Job 触发（trigger=SCHEDULED）。
     *
     * <p>如果传入的 runId 为 null（来自 Quartz 定时触发），自行生成；
     * 立即执行路径通常会预生成 runId 并以 QUEUED 状态先插入，再传入本方法。
     */
    public Future<TaskRun> execute(String taskId, String triggerType, String preCreatedRunId) {
        return asyncPool.submit(() -> doExecute(taskId, triggerType, preCreatedRunId));
    }

    /** Quartz Fire Job 入口：行内生成 runId。 */
    Future<TaskRun> executeFromQuartz(String taskId, String triggerType) {
        return execute(taskId, triggerType, null);
    }

    private TaskRun doExecute(String taskId, String triggerType, String preCreatedRunId) {
        String runId = preCreatedRunId != null ? preCreatedRunId : genRunId();
        long startedAt = System.currentTimeMillis();
        log.info("doExecute ENTER: taskId={}, trigger={}, runId={}", taskId, triggerType, runId);

        // 单飞行检查 + 占位
        String previous = running.putIfAbsent(taskId, runId);
        if (previous != null) {
            log.info("Task '{}' already running as runId={}, skipping new run {}", taskId, previous, runId);
            TaskRun skipped = new TaskRun();
            skipped.setRunId(runId);
            skipped.setTaskId(taskId);
            skipped.setTriggerType(triggerType);
            skipped.setStatus(TaskRun.STATUS_SKIPPED);
            skipped.setStartedAt(startedAt);
            skipped.setFinishedAt(System.currentTimeMillis());
            skipped.setDurationMs(0L);
            skipped.setError("上一次执行仍在进行中（runId=" + previous + "），跳过本次触发");
            try {
                if (preCreatedRunId != null) {
                    // runNow 路径：QUEUED 行已先插入 → UPDATE 状态为 SKIPPED
                    runMapper.finish(skipped);
                } else {
                    // Quartz 触发路径：直接 INSERT
                    runMapper.insert(skipped);
                }
            } catch (Exception e) {
                log.warn("Failed to persist SKIPPED run: {}", e.getMessage());
            }
            return skipped;
        }

        try {
            ScheduledTask task = taskMapper.findById(taskId);
            if (task == null) {
                TaskRun failed = new TaskRun();
                failed.setRunId(runId);
                failed.setTaskId(taskId);
                failed.setTriggerType(triggerType);
                failed.setStatus(TaskRun.STATUS_FAILED);
                failed.setStartedAt(startedAt);
                failed.setFinishedAt(System.currentTimeMillis());
                failed.setError("任务不存在或已被删除");
                runMapper.insert(failed);
                finishRun(failed);
                return failed;
            }
            // 确认关联 agent 还在注册表里
            try {
                registry.getSpec(task.getAgentId());
            } catch (java.util.NoSuchElementException nse) {
                TaskRun failed = new TaskRun();
                failed.setRunId(runId);
                failed.setTaskId(taskId);
                failed.setTriggerType(triggerType);
                failed.setStatus(TaskRun.STATUS_FAILED);
                failed.setStartedAt(startedAt);
                failed.setFinishedAt(System.currentTimeMillis());
                failed.setError("关联数字人不存在：" + task.getAgentId());
                runMapper.insert(failed);
                finishRun(failed);
                return failed;
            }
            return run(task, triggerType, runId, startedAt, preCreatedRunId != null);
        } finally {
            running.remove(taskId, runId);
        }
    }

    private TaskRun run(ScheduledTask task, String triggerType, String runId, long startedAt, boolean preCreated) {
        String sessionId = SESSION_PREFIX + Long.toString(startedAt, 36) + "-" + randomSuffix();
        String prompt = buildPrompt(task);

        // RUNNING 状态：手动执行路径下 QUEUED 已先插入 → UPDATE；定时触发路径 → INSERT
        if (preCreated) {
            TaskRun update = new TaskRun();
            update.setRunId(runId);
            update.setStatus(TaskRun.STATUS_RUNNING);
            update.setStartedAt(startedAt);
            update.setSessionId(sessionId);
            runMapper.markRunning(update);
        } else {
            TaskRun running_ = new TaskRun();
            running_.setRunId(runId);
            running_.setTaskId(task.getId());
            running_.setTriggerType(triggerType);
            running_.setStatus(TaskRun.STATUS_RUNNING);
            running_.setStartedAt(startedAt);
            running_.setSessionId(sessionId);
            runMapper.insert(running_);
        }
        log.info("Task run start: taskId={}, runId={}, trigger={}, sessionId={}",
                task.getId(), runId, triggerType, sessionId);

        long finishedAt;
        String status;
        String replyPreview = null;
        String error = null;
        Integer inT = null, outT = null, cacheT = null, totalT = null;

        try {
            AgentService.Reply reply = agentService.reply(
                    task.getAgentId(), sessionId, "anonymous", prompt, null, DEFAULT_TIMEOUT);
            finishedAt = System.currentTimeMillis();
            status = TaskRun.STATUS_SUCCESS;
            replyPreview = previewOf(reply.reply());
            // 补写 usage 到 session sidecar，使历史页能看到 token 统计
            writeUsagesToSidecar(task.getAgentId(), sessionId, reply.usages());
            // 聚合 tokens
            int ii = 0, oo = 0, cc = 0, tt = 0;
            for (UsageDto u : reply.usages()) {
                if (u == null) continue;
                ii += u.getInputTokens();
                oo += u.getOutputTokens();
                cc += u.getCachedTokens();
                tt += u.getTotalTokens();
            }
            if (!reply.usages().isEmpty()) {
                inT = ii; outT = oo; cacheT = cc; totalT = tt;
            }
        } catch (IllegalStateException timeout) {
            finishedAt = System.currentTimeMillis();
            status = TaskRun.STATUS_TIMEOUT;
            error = "执行超过 " + DEFAULT_TIMEOUT.toMinutes() + " 分钟";
            log.warn("Task run timeout: taskId={}, runId={}", task.getId(), runId);
        } catch (Exception ex) {
            finishedAt = System.currentTimeMillis();
            status = TaskRun.STATUS_FAILED;
            error = truncate(ex.getMessage(), 2000);
            log.error("Task run failed: taskId={}, runId={}", task.getId(), runId, ex);
        }

        TaskRun finalRun = new TaskRun();
        finalRun.setRunId(runId);
        finalRun.setTaskId(task.getId());
        finalRun.setTriggerType(triggerType);
        finalRun.setStatus(status);
        finalRun.setStartedAt(startedAt);
        finalRun.setFinishedAt(finishedAt);
        finalRun.setDurationMs(finishedAt - startedAt);
        finalRun.setSessionId(sessionId);
        finalRun.setReplyPreview(replyPreview);
        finalRun.setError(error);
        finalRun.setTokensInput(inT);
        finalRun.setTokensOutput(outT);
        finalRun.setTokensCached(cacheT);
        finalRun.setTokensTotal(totalT);
        runMapper.finish(finalRun);
        log.info("Task run done: taskId={}, runId={}, status={}, duration={}ms",
                task.getId(), runId, status, finalRun.getDurationMs());
        return finalRun;
    }

    /**
     * 拼装执行 prompt：复用官方 sysPrompt 引导的措辞。
     * skills 非空时强调「必须先加载并遵循」—— 官方唯一强制 skill 的机制。
     */
    private String buildPrompt(ScheduledTask task) {
        StringBuilder sb = new StringBuilder();
        sb.append("【定时任务「").append(task.getName()).append("」自动执行】\n");
        List<String> skills = task.getSkills();
        if (skills != null && !skills.isEmpty()) {
            sb.append("本次任务必须先加载并严格遵循以下技能完成：");
            sb.append(String.join("、", skills));
            sb.append("（使用 load_skill_through_path 加载）。\n");
        }
        sb.append("---\n");
        sb.append(task.getPrompt());
        return sb.toString();
    }

    private void writeUsagesToSidecar(String agentId, String sessionId, List<UsageDto> usages) {
        if (usages == null) return;
        for (UsageDto u : usages) {
            try {
                sessionService.appendUsage(agentId, sessionId, u);
            } catch (Exception e) {
                log.warn("Failed to append usage to sidecar: {}", e.getMessage());
            }
        }
    }

    private void finishRun(TaskRun r) {
        try {
            runMapper.finish(r);
        } catch (Exception e) {
            log.warn("Failed to finalize run {}: {}", r.getRunId(), e.getMessage());
        }
    }

    private String previewOf(String text) {
        if (text == null) return null;
        String stripped = text
                .replaceAll("(?is)<(?:think|reasoning)>.*?</(?:think|reasoning)>", "")
                .trim();
        if (stripped.isEmpty()) return null;
        if (stripped.length() > REPLY_PREVIEW_MAX) {
            return stripped.substring(0, REPLY_PREVIEW_MAX);
        }
        return stripped;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String genRunId() {
        return "r-" + Long.toString(System.currentTimeMillis(), 36) + "-" + randomSuffix();
    }

    private String randomSuffix() {
        return Long.toString((long) (Math.random() * 0xFFFFFFFL), 36);
    }

    /** 通过 AgentRegistry 反查 ScheduledTask 实体（含 prompt/skills/cron 等）。
     *  已废弃：执行路径改用 ScheduledTaskRepository 从 DB 加载任务数据；
     *  AgentRegistry 仅用来校验关联 agent 存在。保留空注释避免导入变更。 */
    // (loadTaskForExecution removed: TaskExecutionService 现在从 DB 读 prompt/skills)

    /**
     * Quartz Job 实现（Quartz 硬性要求每个 JobDetail 绑定一个 {@link Job} 类）。
     * 体内仅一行转发到 {@link #executeFromQuartz}，避免单独文件 / 单独概念。
     *
     * <p>{@code @DisallowConcurrentExecution} = 任务级单飞行（Quartz 任务级互斥）。
     * 集群下 JDBC job store 由 Quartz 通过 QRTZ_LOCKS 行锁保证全集群单跑。
     */
    @DisallowConcurrentExecution
    public static class Fire implements Job {

        @Override
        public void execute(JobExecutionContext context) {
            JobDataMap data = context.getMergedJobDataMap();
            String taskId = data.getString(DATA_TASK_ID);
            String triggerType = data.getString(DATA_TRIGGER_TYPE);
            if (taskId == null || taskId.isBlank()) {
                log.warn("Fire job missing taskId; skipping");
                return;
            }
            if (triggerType == null || triggerType.isBlank()) {
                triggerType = TaskRun.TRIGGER_SCHEDULED;
            }
            Holder.get().executeFromQuartz(taskId, triggerType);
        }
    }

    /** 静态注册表：Quartz 反射实例化 Job 时拿不到 Spring bean —— 借鉴官方 QuartzAgentSchedulerRegistry。 */
    public static final class Holder {
        private static volatile TaskExecutionService exec;

        public static void install(TaskExecutionService service) {
            Holder.exec = service;
        }

        static TaskExecutionService get() {
            return exec;
        }
    }

    /** 应用层单飞行查询（外部用于前置 409 判定）。 */
    public boolean isRunning(String taskId) {
        return running.containsKey(taskId);
    }
}