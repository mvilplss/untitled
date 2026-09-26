package org.example.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 沙箱延迟关闭管理器。
 *
 * <p>Agent 调用结束后不立即销毁沙箱容器，而是等待可配置的空闲超时。
 * 超时前有新调用则重置计时器，超时后才真正执行 shutdown。
 */
@Component
public class SandboxKeepAliveManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxKeepAliveManager.class);

    private final ScheduledExecutorService scheduler;
    private final Map<String, ShutdownHandle> pendingShutdowns = new ConcurrentHashMap<>();
    private volatile long idleTimeoutMinutes = 5;

    public SandboxKeepAliveManager() {
        this.scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sandbox-keepalive");
            t.setDaemon(true);
            return t;
        });
    }

    public void setIdleTimeoutMinutes(long idleTimeoutMinutes) {
        this.idleTimeoutMinutes = idleTimeoutMinutes;
    }

    /**
     * 取消指定沙箱的待关闭任务（新调用到来时调用）。
     */
    public void keepAlive(String sessionId) {
        if (sessionId == null) return;
        ShutdownHandle handle = pendingShutdowns.remove(sessionId);
        if (handle != null) {
            handle.cancelled.set(true);
            handle.future.cancel(false);
            log.debug("[sandbox-keepalive] Cancelled pending shutdown for {}", sessionId);
        }
    }

    /**
     * 调度延迟关闭。同一 sessionId 只保留一个待关闭任务。
     */
    public void scheduleShutdown(String sessionId, Sandbox sandbox) {
        if (sessionId == null || sandbox == null) return;

        // 同 sessionId 去重：取消已有的待关闭任务
        ShutdownHandle existing = pendingShutdowns.remove(sessionId);
        if (existing != null) {
            existing.cancelled.set(true);
            existing.future.cancel(false);
        }

        ShutdownHandle handle = new ShutdownHandle();
        pendingShutdowns.put(sessionId, handle);
        long delay = idleTimeoutMinutes;

        handle.future = scheduler.schedule(() -> {
            if (handle.cancelled.get()) return;
            pendingShutdowns.remove(sessionId, handle);
            try {
                log.info("[sandbox-keepalive] Idle timeout reached, shutting down sandbox {}", sessionId);
                sandbox.shutdown();
            } catch (Exception e) {
                log.warn("[sandbox-keepalive] Delayed shutdown failed for {}: {}", sessionId, e.getMessage());
            }
        }, delay, TimeUnit.MINUTES);

        log.debug("[sandbox-keepalive] Scheduled shutdown for {} in {} min", sessionId, delay);
    }

    /**
     * 立即关闭指定沙箱（close 路径使用，不延迟）。
     */
    public void cancelShutdown(String sessionId) {
        keepAlive(sessionId);
    }

    @PreDestroy
    public void shutdown() {
        log.info("[sandbox-keepalive] Shutting down, force-closing {} pending sandboxes", pendingShutdowns.size());
        for (Map.Entry<String, ShutdownHandle> entry : pendingShutdowns.entrySet()) {
            ShutdownHandle handle = entry.getValue();
            handle.cancelled.set(false); // 强制执行
            handle.future.cancel(false);
        }
        pendingShutdowns.clear();
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static final class ShutdownHandle {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile ScheduledFuture<?> future;
    }
}
