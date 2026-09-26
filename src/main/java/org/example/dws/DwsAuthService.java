package org.example.dws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * dws device-flow 编排：
 * <ol>
 *   <li>POST /auth/device → 生成 userId → 调 {@code dws auth login --device}</li>
 *   <li>早停捕 banner 中的 userCode + verificationUriComplete（dws 在接收 userCode 时已启动 polling 子协程）</li>
 *   <li>后台线程轮询 {@code dws auth status} 检测授权完成</li>
 *   <li>approved 时从 dws 输出解析 corp_id，写 registry</li>
 *   <li>每个 phase 变化通过 {@link DwsAuthEventBus} 推 SSE</li>
 * </ol>
 */
@Service
public class DwsAuthService {

    private static final Logger log = LoggerFactory.getLogger(DwsAuthService.class);

    private final DwsInvoker invoker;
    private final DwsUserDirectory userDirectory;
    private final DwsUserRegistry userRegistry;
    private final DwsAuthEventBus eventBus;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, ActiveFlow> activeFlows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "dws-auth-poll");
                t.setDaemon(true);
                return t;
            });

    public DwsAuthService(DwsInvoker invoker,
                          DwsUserDirectory userDirectory,
                          DwsUserRegistry userRegistry,
                          DwsAuthEventBus eventBus) {
        this.invoker = invoker;
        this.userDirectory = userDirectory;
        this.userRegistry = userRegistry;
        this.eventBus = eventBus;
    }

    /**
     * 发起 device-flow。返回 userId + 设备码信息。
     * 后端异步 polling；前端用 SSE（{@code /api/dws/auth/stream/{userId}}）订阅进度。
     */
    public DeviceFlowInfo startDeviceFlow(String userIdHint) throws IOException {
        String userId = (userIdHint == null || userIdHint.isBlank())
                ? "u-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : userIdHint;
        userDirectory.validate(userId);

        // 准备目录
        userDirectory.configDir(userId);
        userDirectory.keychainDir(userId);

        // dws 在 --device 模式下输出 banner + 等待 + 末尾 JSON。
        // 用早停机制：捕到 "authorization code:" 即强杀进程、立即返回
        DwsInvoker.Result res = invoker.run(userId,
                List.of("auth", "login", "--device"),
                null, null, 60_000L, "authorization code:");

        String stdout = res.stdout == null ? "" : res.stdout;
        DeviceFlowInfo info = new DeviceFlowInfo();
        info.userId = userId;
        info.userCode = extractAfter(stdout, "authorization code:");
        if (info.userCode.isEmpty()) {
            info.userCode = extractAfter(stdout, "user_code=");
        }
        String link = extractAfter(stdout, "Or open the following link:");
        info.verificationUriComplete = link.trim();
        if (info.verificationUriComplete.isEmpty()) {
            info.verificationUriComplete = "https://login.dingtalk.com/oauth2/device/verify.htm";
        }
        info.flowId = "";
        info.intervalMs = 5_000;
        info.expiresIn = 900;
        info.expiresAt = System.currentTimeMillis() + info.expiresIn * 1000L;

        if (info.userCode.isEmpty()) {
            // dws userDir 已登录态：banner 跳过、直接输出 success JSON
            if (stdout.contains("\"success\": true") || stdout.contains("Token 有效")) {
                registerAlreadyApproved(userId, stdout);
                return info;
            }
            throw new DwsException("device_flow_parse_failed",
                    "无法从 dws 输出中解析 userCode，请查看日志: " + truncate(stdout));
        }

        // 启动后台 polling
        ActiveFlow flow = new ActiveFlow(userId, info.flowId, info.expiresAt, info.intervalMs);
        activeFlows.put(userId, flow);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("userId", userId);
        payload.put("userCode", info.userCode);
        payload.put("verificationUriComplete", info.verificationUriComplete);
        payload.put("expiresAt", info.expiresAt);
        payload.put("intervalMs", info.intervalMs);
        eventBus.publishPhase(DwsAuthEventBus.AuthPhase.WAITING, payload);

        scheduler.scheduleAtFixedRate(this::pollSafely,
                info.intervalMs, info.intervalMs, TimeUnit.MILLISECONDS);

        return info;
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Throwable t) {
            log.error("dws auth poll crashed", t);
        }
    }

    private void pollOnce() {
        if (activeFlows.isEmpty()) return;
        for (ActiveFlow flow : new ArrayList<>(activeFlows.values())) {
            if (System.currentTimeMillis() > flow.expiresAt) {
                activeFlows.remove(flow.userId);
                ObjectNode p = mapper.createObjectNode();
                p.put("userId", flow.userId);
                p.put("reason", "device_code_expired");
                eventBus.publishPhase(DwsAuthEventBus.AuthPhase.EXPIRED, p);
                continue;
            }
            try {
                DwsInvoker.Result res = invoker.run(flow.userId,
                        List.of("auth", "status", "--format", "json"),
                        null, null, 15_000L);
                JsonNode root = mapper.readTree(res.stdout);
                String status = root.path("status").asText(root.path("authenticated").asText("unknown"));
                if ("approved".equalsIgnoreCase(status) || root.path("authenticated").asBoolean(false)) {
                    handleApproved(flow);
                }
            } catch (DwsException.InvokeFailed ife) {
                if (ife.getExitCode() != 2) {
                    log.warn("dws auth poll exit={}: {}", ife.getExitCode(), ife.getStderr());
                }
            } catch (Exception e) {
                log.warn("dws auth poll error: {}", e.getMessage());
            }
        }
    }

    private void handleApproved(ActiveFlow flow) throws IOException {
        activeFlows.remove(flow.userId);
        DwsInvoker.Result status = invoker.run(flow.userId,
                List.of("auth", "status", "--format", "json"),
                null, null, 15_000L);

        DwsUserRegistry.RegistryEntry entry = new DwsUserRegistry.RegistryEntry();
        try {
            JsonNode root = mapper.readTree(status.stdout);
            entry.corpId = root.path("corp_id").asText("");
            entry.userId = root.path("user_id").asText("");
        } catch (Exception ignored) { /* 解析失败留空 */ }
        entry.status = "active";
        entry.lastLoginAt = System.currentTimeMillis();

        userRegistry.upsert(flow.userId, entry);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("userId", flow.userId);
        ObjectNode prof = payload.putObject("profile");
        prof.put("corpId", entry.corpId);
        prof.put("corpName", entry.corpName);
        prof.put("userId", entry.userId);
        eventBus.publishPhase(DwsAuthEventBus.AuthPhase.APPROVED, payload);
    }

    /**
     * dws userDir 已登录状态：从 auth login 末尾输出解析 corp_id。
     */
    private void registerAlreadyApproved(String userId, String stdout) throws IOException {
        DwsUserRegistry.RegistryEntry entry = new DwsUserRegistry.RegistryEntry();
        try {
            int jsonStart = stdout.indexOf('{');
            int jsonEnd = stdout.lastIndexOf('}');
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                JsonNode root = mapper.readTree(stdout.substring(jsonStart, jsonEnd + 1));
                entry.corpId = root.path("corp_id").asText("");
                entry.userId = root.path("user_id").asText("");
            }
        } catch (Exception ignored) { }
        entry.status = "active";
        entry.lastLoginAt = System.currentTimeMillis();

        userRegistry.upsert(userId, entry);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("userId", userId);
        ObjectNode prof = payload.putObject("profile");
        prof.put("corpId", entry.corpId);
        prof.put("corpName", entry.corpName);
        prof.put("userId", entry.userId);
        eventBus.publishPhase(DwsAuthEventBus.AuthPhase.APPROVED, payload);
    }

    /** 列出该 userId 当前绑定的 profile 信息（透传 dws auth status） */
    public JsonNode listProfiles(String userId) throws IOException {
        userDirectory.validate(userId);
        try {
            DwsInvoker.Result res = invoker.run(userId,
                    List.of("auth", "status", "--format", "json"),
                    null, null, 15_000L);
            return mapper.readTree(res.stdout);
        } catch (DwsException.InvokeFailed e) {
            if (e.getExitCode() == 2) {
                throw new DwsException.Unauthorized("userId '" + userId + "' 尚未授权或 token 失效");
            }
            throw e;
        }
    }

    /** 透传 dws profile switch（持久化默认 profile） */
    public void switchProfile(String userId, String selector) throws IOException {
        userDirectory.validate(userId);
        invoker.run(userId,
                List.of("profile", "switch", selector),
                null, null, 15_000L);
    }

    /** 移除单 profile */
    public void removeProfile(String userId, String selector) throws IOException {
        userDirectory.validate(userId);
        invoker.run(userId,
                List.of("auth", "logout", "--profile", selector, "--format", "json"),
                null, null, 15_000L);
    }

    /** 清空该 userId 所有 profile + 本地 config/keychain */
    public void reset(String userId) throws IOException {
        userDirectory.validate(userId);
        try {
            invoker.run(userId, List.of("auth", "reset", "--format", "json"),
                    null, null, 15_000L);
        } catch (DwsException.InvokeFailed ignored) {
            // 即便没授权也允许 reset
        }
        userRegistry.remove(userId);
        Path userDir = userDirectory.resolveUserDir(userId);
        if (Files.exists(userDir)) {
            try (var walk = Files.walk(userDir)) {
                walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored2) {} });
            }
        }
    }

    /** 当前活动 flow 状态 */
    public DwsAuthEventBus.AuthPhase currentPhase() {
        return eventBus.currentPhase();
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    private static String extractAfter(String text, String key) {
        if (text == null || text.isEmpty()) return "";
        int idx = text.indexOf(key);
        if (idx < 0) return "";
        String rest = text.substring(idx + key.length());
        for (String tok : rest.split("\\s+")) {
            if (!tok.isEmpty()) return tok;
        }
        return "";
    }

    private static class ActiveFlow {
        final String userId;
        final String flowId;
        final long expiresAt;
        final long intervalMs;
        ActiveFlow(String userId, String flowId, long expiresAt, long intervalMs) {
            this.userId = userId;
            this.flowId = flowId;
            this.expiresAt = expiresAt;
            this.intervalMs = intervalMs;
        }
    }

    public static class DeviceFlowInfo {
        public String userId;
        public String flowId;
        public String userCode;
        public String verificationUriComplete;
        public long intervalMs;
        public int expiresIn;
        public long expiresAt;
    }
}
