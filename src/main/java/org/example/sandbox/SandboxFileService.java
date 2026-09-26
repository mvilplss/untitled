package org.example.sandbox;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.sandbox.PinnedSandboxFilesystem;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.SandboxIsolationKey;
import io.agentscope.harness.agent.sandbox.SandboxManager;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.SessionSandboxStateStore;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState;
import org.example.agent.AgentRegistry;
import org.example.agent.AgentRegistry.SandboxHandle;
import org.example.sandbox.dto.SandboxFileDtos.FileContentResponse;
import org.example.sandbox.dto.SandboxFileDtos.FileEntry;
import org.example.sandbox.dto.SandboxFileDtos.FileListResponse;
import org.example.sandbox.dto.SandboxFileDtos.SandboxStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 沙箱文件系统浏览服务（只读）。
 *
 * <p><b>核心思路</b>：不依赖任何 agent 调用的 sandbox lifecycle（那是 chat 路径的）。
 * 直接复用 AgentRegistry 已构造的 {@link io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec}
 * + 同一个 {@link SandboxKeepAliveManager}，通过 {@link SandboxManager#acquire}（Priority 3
 * 从持久化 state resume，同 USER scope key 命中同一容器；缺失时 Priority 4 创建新容器
 * 并由 {@link Sandbox#start} 自愈）。最终把 {@link SandboxAcquireResult} 放进我们自构造的
 * {@link RuntimeContext} 喂给 {@link PinnedSandboxFilesystem}，让框架的
 * {@link AbstractFilesystem#ls} / {@link AbstractFilesystem#read} / {@link AbstractFilesystem#downloadFiles}
 * 帮我们做路径校验 + shell 命令拼装 + 分页 + base64 二进制处理。
 *
 * <p><b>生命周期</b>：acquire 后<b>不调用</b> {@link SandboxManager#release}——避免 stop() 触发
 * 全量 workspace tar 快照（成本高且对只读场景无必要）。容器由 chat 路径的 release 自然接管，
 * 5 分钟空闲后才销毁；浏览器期间每请求构造 SandboxAcquireResult，但容器保持运行。
 *
 * <p><b>路径约束</b>：所有访问必须落在 {@code workspaceRoot}（默认 /workspace）内。
 */
@Component
public class SandboxFileService {

    private static final Logger log = LoggerFactory.getLogger(SandboxFileService.class);

    /** 内容预览默认分页：单次最多读 2000 行（与框架 BaseSandboxFilesystem.read 默认一致）。 */
    private static final int DEFAULT_READ_LIMIT = 2000;
    /** 单文件内容预览阈值：超过此字节数返回 tooLarge=true，前端降级为 raw 下载。 */
    private static final long PREVIEW_MAX_BYTES = 2L * 1024 * 1024;

    private final AgentRegistry agentRegistry;

    public SandboxFileService(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    /**
     * 探测沙箱状态：仅读持久化 state，不 acquire、不 docker I/O。
     * 容器是否真的在运行由 chat / wake 路径保证，本方法返回的是「能否 resume 到容器」。
     * 同时从 state json 提取 containerId 让前端可直观对比与 docker ps 一致性。
     */
    public SandboxStatusResponse status(String agentId, String userId) {
        SandboxHandle handle = agentRegistry.getSandboxHandle(agentId);
        String uId = normalizeUserId(userId);
        SessionSandboxStateStore stateStore = new SessionSandboxStateStore(
                new JsonFileAgentStateStore(defaultStateDir(handle.agentId())), handle.agentId());
        RuntimeContext rc = RuntimeContext.builder().userId(uId).sessionId(normalizeSessionId(null)).build();
        // 跟随 agent fsSpec 的 isolation scope（与 chat 路径保持同 key 空间，避免错位）
        IsolationScope scope = handle.fsSpec().getIsolationScope();
        Optional<SandboxIsolationKey> keyOpt =
                SandboxIsolationKey.resolve(scope, rc, handle.agentId());
        if (keyOpt.isEmpty()) {
            return new SandboxStatusResponse(false, defaultWorkspaceRoot(), null, agentId);
        }
        try {
            Optional<String> stateJson = stateStore.load(keyOpt.get());
            if (stateJson.isEmpty()) {
                return new SandboxStatusResponse(false, defaultWorkspaceRoot(), null, agentId);
            }
            // 提取 containerId / workspaceRoot。state json 是嵌套结构（value = stateStore entry）
            String containerId = extractField(stateJson.get(), "containerId");
            String workspaceRoot = extractField(stateJson.get(), "workspaceRoot");
            return new SandboxStatusResponse(
                    true,
                    workspaceRoot != null ? workspaceRoot : defaultWorkspaceRoot(),
                    containerId,
                    agentId);
        } catch (Exception e) {
            log.debug("status load failed for {}: {}", agentId, e.getMessage());
            return new SandboxStatusResponse(false, defaultWorkspaceRoot(), null, agentId);
        }
    }

    /** 简单 JSON 字段提取（state json 体里就是 json 字串）。 */
    private static String extractField(String stateJson, String field) {
        try {
            int i = stateJson.indexOf("\"" + field + "\"");
            if (i < 0) return null;
            int colon = stateJson.indexOf(':', i);
            int quote1 = stateJson.indexOf('"', colon + 1);
            int quote2 = stateJson.indexOf('"', quote1 + 1);
            if (quote1 < 0 || quote2 < 0) return null;
            return stateJson.substring(quote1 + 1, quote2);
        } catch (Exception e) {
            return null;
        }
    }

    /** 目录列表（懒加载树节点）。state 不存在 → 409。 */
    public FileListResponse list(String agentId, String userId, String sessionId, String path) {
        FsHandle fs = acquireOrThrow(agentId, userId, sessionId, false);
        try {
            String norm = normalizeAndContain(path, fs.workspaceRoot);
            LsResult res = fs.filesystem.ls(fs.runtimeContext, norm);
            if (!res.isSuccess()) {
                throw mapLsError(res.error(), norm);
            }
            List<FileEntry> entries = new ArrayList<>();
            for (var fi : res.entries()) {
                entries.add(new FileEntry(basename(fi.path()), fi.path(), fi.isDirectory(), fi.size(),
                        fi.modifiedAt() == null ? "" : fi.modifiedAt()));
            }
            return new FileListResponse(norm, entries);
        } finally {
            releaseLease(fs);
        }
    }

    /**
     * 读取文件内容用于预览。
     *
     * @param offset 行偏移（0-indexed）
     * @param limit  最大行数，<=0 用默认 2000
     */
    public FileContentResponse content(String agentId, String userId, String sessionId,
                                       String path, int offset, int limit) {
        FsHandle fs = acquireOrThrow(agentId, userId, sessionId, false);
        try {
            String norm = normalizeAndContain(path, fs.workspaceRoot);
            int effLimit = limit > 0 ? limit : DEFAULT_READ_LIMIT;
            ReadResult res = fs.filesystem.read(fs.runtimeContext, norm, offset, effLimit);
            if (!res.isSuccess()) {
                throw mapReadError(res.error(), norm);
            }
            var data = res.fileData();
            boolean isBase64 = "base64".equalsIgnoreCase(data.encoding());
            long contentBytes = data.content() == null ? 0L
                    : data.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            boolean tooLarge = !isBase64 && contentBytes > PREVIEW_MAX_BYTES;
            String content = tooLarge ? "" : data.content();
            // truncated 启发式：非空 utf-8 内容且行数已达 limit。前端提示「已截断，加载更多或下载」。
            boolean truncated = !isBase64 && !tooLarge && data.content() != null
                    && data.content().split("\n", -1).length >= effLimit;
            return new FileContentResponse(norm, data.encoding(), content, contentBytes, truncated, tooLarge);
        } finally {
            releaseLease(fs);
        }
    }

    /**
     * 读取文件原始字节用于下载 / 图片预览。走 {@link AbstractFilesystem#downloadFiles}：
     * 框架内部处理二进制（base64）与文本（utf-8），对外统一返回 byte[]。
     */
    public DownloadedFile download(String agentId, String userId, String sessionId, String path) {
        FsHandle fs = acquireOrThrow(agentId, userId, sessionId, false);
        try {
            String norm = normalizeAndContain(path, fs.workspaceRoot);
            List<FileDownloadResponse> res = fs.filesystem.downloadFiles(
                    fs.runtimeContext, List.of(norm));
            if (res.isEmpty() || !res.get(0).isSuccess()) {
                String err = res.isEmpty() ? "no response" : res.get(0).error();
                throw mapDownloadError(err, norm);
            }
            FileDownloadResponse r = res.get(0);
            return new DownloadedFile(norm, basename(norm), r.content());
        } finally {
            releaseLease(fs);
        }
    }

    /**
     * 显式唤醒沙箱：Priority 3 命中现有 state → start 自愈；state 缺失时由 Priority 4 新建容器。
     * 完成后<b>不 release</b>，让 chat 路径或 keepalive 自然回收。
     */
    public SandboxStatusResponse wake(String agentId, String userId, String sessionId) {
        SandboxHandle handle = agentRegistry.getSandboxHandle(agentId);
        FsHandle fs = acquireOrThrow(handle, userId, sessionId, /* allowCreate */ true);
        try {
            String containerId = containerIdOf(fs.sandbox);
            return new SandboxStatusResponse(true, fs.workspaceRoot, containerId, agentId);
        } finally {
            releaseLease(fs);
        }
    }

    /**
     * 将文件列表上传到沙箱容器内（通过 {@code docker cp}）。
     * <p>在 chat send 前调用，确保 attachment 在沙箱内可见。
     * <p>框架 {@code AbstractFilesystem.uploadFiles} 内部优先走
     * {@code SandboxFileTransfer.uploadFile}（即 {@code docker cp}），
     * 支持本地 / 远程 Docker daemon，无需 bind mount。
     *
     * @param agentId  Agent ID
     * @param userId   用户标识（sandbox USER scope）
     * @param sessionId 会话 ID（sandbox lifecycle）
     * @param files    (containerPath, content) 列表，containerPath 形如 /workspace/uploads/<userId>/xxx
     */
    public void uploadAttachments(String agentId, String userId, String sessionId,
                                  List<Map.Entry<String, byte[]>> files) throws Exception {
        if (files == null || files.isEmpty()) return;
        FsHandle fs = acquireOrThrow(agentId, userId, sessionId, /* allowCreate */ true);
        try {
            fs.filesystem.uploadFiles(fs.runtimeContext, files);
        } finally {
            releaseLease(fs);
        }
    }

    // ============================================================
    // 内部：acquire / release / 异常映射
    // ============================================================

    private FsHandle acquireOrThrow(String agentId, String userId, String sessionId,
                                    boolean allowCreate) {
        return acquireOrThrow(agentRegistry.getSandboxHandle(agentId), userId, sessionId, allowCreate);
    }

    /**
     * acquire + start + 持久化 state；返回的文件系统已可直接 ls/read/download。
     *
     * @param allowCreate state 缺失时是否允许 Priority 4 新建（true 仅用于 wake 路径）
     */
    private FsHandle acquireOrThrow(SandboxHandle handle, String userId, String sessionId,
                                     boolean allowCreate) {
        Path stateDir = defaultStateDir(handle.agentId());
        SessionSandboxStateStore stateStore =
                new SessionSandboxStateStore(new JsonFileAgentStateStore(stateDir), handle.agentId());
        SandboxContext sctx = handle.fsSpec().toSandboxContext(handle.hostWorkspaceRoot());
        SandboxManager manager = new SandboxManager(
                sctx.getClient(), stateStore, handle.agentId(), SandboxExecutionGuard.noop());

        String uId = normalizeUserId(userId);
        String sId = normalizeSessionId(sessionId);
        RuntimeContext rc = RuntimeContext.builder()
                .userId(uId)
                .sessionId(sId)
                .build();

        // 跟随 agent fsSpec 的 isolation scope（与 chat 路径保持同 key 空间，避免错位）
        IsolationScope scope = handle.fsSpec().getIsolationScope();
        Optional<SandboxIsolationKey> keyOpt =
                SandboxIsolationKey.resolve(scope, rc, handle.agentId());
        if (keyOpt.isEmpty()) {
            throw new IllegalStateException("无法解析沙箱隔离 key：userId 必须非空");
        }
        // 先看 state 是否存在：不存在 + !allowCreate → 立即 409（避免 Priority 4 误创建）
        boolean stateExists;
        try {
            stateExists = stateStore.load(keyOpt.get()).isPresent();
        } catch (Exception e) {
            stateExists = false;
            log.debug("state load failed for {} user {}: {}", handle.agentId(), userId, e.getMessage());
        }
        if (!stateExists && !allowCreate) {
            throw new IllegalStateException(
                    "沙箱未启动：未找到该用户的持久化 state。请先与数字人发起一次对话，或调用 /sandbox/wake 显式唤醒。");
        }

        SandboxAcquireResult result;
        try {
            result = manager.acquire(sctx, rc);
        } catch (Exception e) {
            throw new IllegalStateException("沙箱获取失败：" + e.getMessage(), e);
        }
        try {
            result.getSandbox().start();
        } catch (Exception e) {
            manager.release(result);
            throw new IllegalStateException("沙箱启动失败：" + e.getMessage(), e);
        }
        // 幂等：覆盖式写回 state（Priority 3 时基本无变化，Priority 4 时首次落盘）
        manager.persistState(result, sctx, rc);

        String wsRoot = workspaceRootOf(result.getSandbox());
        RuntimeContext fsRc = RuntimeContext.builder()
                .userId(uId)
                .sessionId(sId)
                .put(SandboxAcquireResult.class, result)
                .build();
        PinnedSandboxFilesystem fs = new PinnedSandboxFilesystem(result.getSandbox());
        return new FsHandle(fs, fsRc, result, result.getSandbox(), wsRoot);
    }

    private boolean hasPersistedState(String agentId, String userId) {
        SessionSandboxStateStore stateStore = new SessionSandboxStateStore(
                new JsonFileAgentStateStore(defaultStateDir(agentId)), agentId);
        String uId = normalizeUserId(userId);
        RuntimeContext rc = RuntimeContext.builder().userId(uId).sessionId(normalizeSessionId(null)).build();
        // 跟随 agent fsSpec 的 isolation scope（与其他三处保持一致）
        IsolationScope scope = agentRegistry.getSandboxHandle(agentId).fsSpec().getIsolationScope();
        Optional<SandboxIsolationKey> keyOpt =
                SandboxIsolationKey.resolve(scope, rc, agentId);
        if (keyOpt.isEmpty()) return false;
        try {
            return stateStore.load(keyOpt.get()).isPresent();
        } catch (Exception e) {
            log.debug("hasPersistedState failed for {}: {}", agentId, e.getMessage());
            return false;
        }
    }

    /**
     * 对齐 {@code AgentService.ctx()} 的兜底：空白 userId → "anonymous"、空白 sessionId → "default"。
     * 否则空 userId 会让 SandboxIsolationKey USER scope 降级到 SESSION scope（用 sessionId 作值），
     * key 整体错位；且空白 sessionId 会让 chat 路径走 "default" 而文件浏览走 ""，同样错位。
     */
    private static String normalizeUserId(String userId) {
        return (userId == null || userId.isBlank()) ? "anonymous" : userId;
    }

    private static String normalizeSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
    }

    /** 关闭 lease 并把 result 释放掉（这里只 lease.close + 标记 GC，不 stop 容器）。 */
    private void releaseLease(FsHandle fs) {
        try {
            fs.result.getLease().close();
        } catch (Exception ignored) {
            // lease 是 noop（executionGuard=noop），close 永远安全
        }
    }

    /** wake 失败时主动 release 一次，避免 start 失败留下半构造对象。 */
    private void releaseResult(SandboxManager manager, SandboxAcquireResult result) {
        try {
            manager.release(result);
        } catch (Exception e) {
            log.debug("release on failure path: {}", e.getMessage());
        }
    }

    private static String workspaceRootOf(Sandbox sandbox) {
        try {
            SandboxState st = sandbox.getState();
            if (st instanceof DockerSandboxState dss && dss.getWorkspaceRoot() != null
                    && !dss.getWorkspaceRoot().isBlank()) {
                return dss.getWorkspaceRoot();
            }
        } catch (Exception ignored) {}
        return defaultWorkspaceRoot();
    }

    private static String containerIdOf(Sandbox sandbox) {
        try {
            SandboxState st = sandbox.getState();
            if (st instanceof DockerSandboxState dss) {
                return dss.getContainerId();
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ============================================================
    // 路径校验
    // ============================================================

    /**
     * 路径校验：必须是 / 开头的绝对路径、无 ..、落在 workspaceRoot 内。
     * 校验通过后返回 {@link Path#normalize} 后的字符串形式。
     */
    private String normalizeAndContain(String raw, String workspaceRoot) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        AbstractFilesystem.validatePath(raw);
        String ws = workspaceRoot == null ? defaultWorkspaceRoot() : workspaceRoot;
        Path norm = Paths.get(raw).normalize();
        Path wsNorm = Paths.get(ws).normalize();
        if (!norm.startsWith(wsNorm)) {
            throw new IllegalArgumentException(
                    "path 必须在沙箱工作区根目录 " + ws + " 内：" + raw);
        }
        return norm.toString();
    }

    private static String basename(String path) {
        if (path == null || path.isEmpty()) return "";
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static String defaultWorkspaceRoot() {
        return "/workspace";
    }

    /**
     * 复刻 {@code io.agentscope.harness.agent.HarnessAgent#defaultStateDir} 的逻辑：
     * 优先 {@code agentscope.state.home} 系统属性，回落 {@code ~/.agentscope/state}。
     * 该方法是 harness 包私有 static，外部无法直接调用。
     */
    private static Path defaultStateDir(String agentId) {
        String override = System.getProperty("agentscope.state.home");
        Path root = (override != null && !override.isBlank())
                ? Paths.get(override)
                : Paths.get(System.getProperty("user.home"), ".agentscope", "state");
        return root.resolve(agentId);
    }

    // ============================================================
    // 错误 → 异常映射
    // ============================================================

    private static RuntimeException mapLsError(String err, String path) {
        if (err == null) return new IllegalStateException("未知 ls 错误");
        String low = err.toLowerCase();
        if (low.contains("does not exist") || low.contains("file_not_found")) {
            return new org.example.web.NotFoundException("路径不存在：" + path);
        }
        if (low.contains("not a directory")) {
            return new IllegalArgumentException("不是目录：" + path);
        }
        return new IllegalStateException("ls 失败：" + err);
    }

    private static RuntimeException mapReadError(String err, String path) {
        if (err == null) return new IllegalStateException("未知 read 错误");
        if (err.contains("file_not_found")) {
            return new org.example.web.NotFoundException("文件不存在：" + path);
        }
        return new IllegalStateException("read 失败：" + err);
    }

    private static RuntimeException mapDownloadError(String err, String path) {
        if (err == null) return new IllegalStateException("未知 download 错误");
        if (err.contains("file_not_found") || err.contains("No such file")) {
            return new org.example.web.NotFoundException("文件不存在：" + path);
        }
        return new IllegalStateException("download 失败：" + err);
    }

    // ============================================================
    // 内部数据结构
    // ============================================================

    /** acquire 之后的对象组合，try-finally 块统一释放 lease。 */
    private record FsHandle(PinnedSandboxFilesystem filesystem,
                            RuntimeContext runtimeContext,
                            SandboxAcquireResult result,
                            Sandbox sandbox,
                            String workspaceRoot) {}

    /** 文件原始字节 + 文件名，给 Controller 写 Content-Disposition / Content-Type。 */
    public record DownloadedFile(String path, String name, byte[] bytes) {}
}
