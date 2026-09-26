package org.example.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Spring Boot 启动完成后：把 DB 中所有 enabled 任务对齐到 Quartz（补 trigger / pause 孤儿）。
 * 与 AgentRegistry 启动加载语义同款（registry.loadFromPersistence → 走完自动 channel 同步）。
 */
@Component
@Order(0)
public class StartupReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupReconciler.class);

    private final TaskSchedulerService taskScheduler;

    public StartupReconciler(TaskSchedulerService taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            taskScheduler.reconcileOnStartup();
            log.info("Task startup reconciliation done");
        } catch (Exception e) {
            log.error("Task startup reconciliation failed", e);
        }
    }
}