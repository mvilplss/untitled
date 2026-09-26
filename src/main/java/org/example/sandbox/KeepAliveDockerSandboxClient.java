package org.example.sandbox;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;

/**
 * 支持延迟关闭的 Docker 沙箱客户端。
 *
 * <p>继承 {@link DockerSandboxClient}，在 {@code create()} / {@code resume()} 返回时
 * 用 {@link KeepAliveSandbox} 包装，使 {@code shutdown()} 走延迟路径。
 */
public class KeepAliveDockerSandboxClient extends DockerSandboxClient {

    private final SandboxKeepAliveManager keepAliveManager;

    public KeepAliveDockerSandboxClient(SandboxKeepAliveManager keepAliveManager) {
        super();
        this.keepAliveManager = keepAliveManager;
    }

    @Override
    public Sandbox create(WorkspaceSpec workspaceSpec, SandboxSnapshotSpec snapshotSpec,
                          DockerSandboxClientOptions options) {
        Sandbox sandbox = super.create(workspaceSpec, snapshotSpec, options);
        return new KeepAliveSandbox(sandbox, keepAliveManager);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        Sandbox sandbox = super.resume(state);
        return new KeepAliveSandbox(sandbox, keepAliveManager);
    }
}
