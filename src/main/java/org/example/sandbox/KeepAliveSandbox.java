package org.example.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

/**
 * 延迟关闭的沙箱包装器。
 *
 * <p>{@code shutdown()} 不立即销毁容器，而是交给 {@link SandboxKeepAliveManager} 调度延迟执行。
 * {@code close()} 仍然立即销毁（Agent 删除 / 更新 / 应用退出时使用）。
 */
public class KeepAliveSandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveSandbox.class);

    private final Sandbox delegate;
    private final SandboxKeepAliveManager keepAliveManager;

    public KeepAliveSandbox(Sandbox delegate, SandboxKeepAliveManager keepAliveManager) {
        this.delegate = delegate;
        this.keepAliveManager = keepAliveManager;
    }

    @Override
    public void start() throws Exception {
        // 新调用到来：取消待关闭任务，重置计时器
        keepAliveManager.keepAlive(sessionId());
        delegate.start();
    }

    @Override
    public void stop() throws Exception {
        delegate.stop();
    }

    @Override
    public void shutdown() throws Exception {
        // 延迟关闭：调度后不立即销毁容器
        String sid = sessionId();
        log.debug("[sandbox-keepalive] Delaying shutdown for {}", sid);
        keepAliveManager.scheduleShutdown(sid, delegate);
    }

    @Override
    public void close() throws Exception {
        // 立即关闭：取消待关闭任务，直接销毁
        keepAliveManager.cancelShutdown(sessionId());
        delegate.close();
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }

    @Override
    public SandboxState getState() {
        return delegate.getState();
    }

    @Override
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds) throws Exception {
        return delegate.exec(runtimeContext, command, timeoutSeconds);
    }

    @Override
    public InputStream persistWorkspace() throws Exception {
        return delegate.persistWorkspace();
    }

    @Override
    public void hydrateWorkspace(InputStream archive) throws Exception {
        delegate.hydrateWorkspace(archive);
    }

    private String sessionId() {
        SandboxState state = delegate.getState();
        return state != null ? state.getSessionId() : "unknown";
    }
}
