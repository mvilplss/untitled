package org.example.task;

import org.example.agent.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 把 AgentRegistry 注册到 TaskSchedulerService 静态 holder，
 * 让 list 接口能拿 AgentSpec.name 做 agentName 修饰。
 */
@Component
@Order(-1) // 早于 StartupReconciler
public class TaskHolderBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskHolderBootstrap.class);

    private final AgentRegistry registry;

    public TaskHolderBootstrap(AgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        TaskSchedulerService.SpringContextHolder.install(registry);
        log.debug("Installed AgentRegistry into TaskSchedulerService holder");
    }
}