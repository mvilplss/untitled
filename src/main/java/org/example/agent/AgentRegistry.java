package org.example.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.snapshot.LocalSnapshotSpec;
import io.agentscope.harness.agent.tools.ToolsConfig;
import jakarta.annotation.PreDestroy;
import org.example.bailian.BailianKnowledgeTool;
import org.example.bailian.BailianRagConfig;
import org.example.bailian.BailianRagService;
import org.example.bot.DingTalkBotConfig;
import org.example.bot.DingTalkChannelRegistry;
import org.example.config.AgentProperties;
import org.example.config.ModelFactory;
import org.example.sandbox.KeepAliveDockerSandboxClient;
import org.example.sandbox.SandboxKeepAliveManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class AgentRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentRegistry.class);

    private final ModelFactory modelFactory;
    private final AgentProperties.OpenAI cfg;
    private final AgentProperties.Config globalCfg;
    private final AgentProperties.Sandbox sandboxCfg;
    private final Path skillsDir;
    private final AgentPersistence persistence;
    private final DingTalkChannelRegistry channelRegistry;
    private final BailianRagService bailianService;
    private final SandboxKeepAliveManager sandboxKeepAliveManager;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ConcurrentHashMap<String, AgentEntry> map = new ConcurrentHashMap<>();
    /** 与 agent 一一对应的沙箱句柄（fsSpec + 宿主 workspace 根）；供 SandboxFileService 做目录浏览。 */
    private final ConcurrentHashMap<String, SandboxHandle> sandboxHandles = new ConcurrentHashMap<>();

    public AgentRegistry(ModelFactory modelFactory,
                        AgentProperties props,
                        AgentPersistence persistence,
                        DingTalkChannelRegistry channelRegistry,
                        BailianRagService bailianService,
                        SandboxKeepAliveManager sandboxKeepAliveManager) {
        this.modelFactory = modelFactory;
        this.cfg = props.getOpenai();
        this.globalCfg = props.getConfig();
        this.sandboxCfg = props.getSandbox();
        this.skillsDir = Paths.get(globalCfg.getSkillsDir()).toAbsolutePath().normalize();
        this.persistence = persistence;
        this.channelRegistry = channelRegistry;
        this.bailianService = bailianService;
        this.sandboxKeepAliveManager = sandboxKeepAliveManager;
        this.sandboxKeepAliveManager.setIdleTimeoutMinutes(sandboxCfg.getIdleTimeoutMinutes());
        loadFromPersistence();
    }

    /** 返回指定 agent 的沙箱句柄（fsSpec + workspaceRoot + agentId），用于 SandboxFileService 构造文件系统访问。 */
    public SandboxHandle getSandboxHandle(String agentId) {
        SandboxHandle handle = sandboxHandles.get(agentId);
        if (handle == null) {
            throw new NoSuchElementException("sandbox handle for agent '" + agentId + "' not found");
        }
        return handle;
    }

    public SandboxKeepAliveManager getSandboxKeepAliveManager() {
        return sandboxKeepAliveManager;
    }

    public AgentSpec create(AgentSpec spec) {
        // 校验 dingtalk：enabled=true 时三个字段必填；任一缺失提前抛错，避免创建后 channel 启动失败
        DingTalkBotConfig bot = spec.getDingtalkRaw();
        if (bot != null && bot.isEnabled() && !bot.isComplete()) {
            throw new IllegalArgumentException(
                    "dingtalk 配置不完整：appKey / appSecret / robotCode 必填");
        }
        // bailian 在 buildAgent 阶段按合并后配置判定是否启用，不在此处抛错

        HarnessAgent agent = buildAgent(spec);
        AgentEntry entry = new AgentEntry(spec, modelFactory.getOrBuild(spec.getModelName()), agent);
        AgentEntry existing = map.putIfAbsent(spec.getId(), entry);
        if (existing != null) {
            try { agent.close(); } catch (Exception ignored) {}
            throw new IllegalStateException("agent '" + spec.getId() + "' already exists");
        }

        channelRegistry.registerAgent(spec.getId(), agent);
        applyBotConfig(spec.getId(), spec.getDingtalkRaw(), null);

        persistAll();
        log.info("Registered agent: id={}, name={}, model={}, tools={}, skills={}, dingtalk={}, bailian={}",
                spec.getId(), spec.getName(), spec.getModelName(), spec.getTools(), spec.getSkills(),
                spec.getDingtalk() != null && spec.getDingtalk().isEnabled(),
                spec.getBailian() != null && spec.getBailian().isEnabled());
        return spec;
    }

    /** 用新 spec 替换现有 Agent：先 close 再重建。会丢弃内存中 in-flight 事件。 */
    public AgentSpec update(String id, AgentSpec spec) {
        AgentEntry old = map.get(id);
        if (old == null) {
            throw new NoSuchElementException("agent '" + id + "' not found");
        }

        // 校验 dingtalk：PUT /api/agents/{id} 也支持带 dingtalk 字段；同时通过 PUT /dingtalk 子接口
        // 单字段启用时，三个字段必须齐
        DingTalkBotConfig newBot = spec.getDingtalkRaw();
        if (newBot != null && newBot.isEnabled() && !newBot.isComplete()) {
            throw new IllegalArgumentException(
                    "dingtalk 配置不完整：appKey / appSecret / robotCode 必填");
        }

        // 主表单 PUT 不带 dingtalk 时保留旧值——避免「编辑名字」把钉钉机器人一并清空
        if (newBot == null) {
            spec.setDingtalk(old.spec.getDingtalkRaw());
        }

        // bailian：保留策略同 dingtalk；spec 不带 bailian 字段时保留旧值
        if (spec.getBailianRaw() == null) {
            spec.setBailian(old.spec.getBailianRaw());
        }

        HarnessAgent newAgent = buildAgent(spec);
        Model newModel = modelFactory.getOrBuild(spec.getModelName());
        AgentEntry newEntry = new AgentEntry(spec, newModel, newAgent);
        map.put(id, newEntry);

        channelRegistry.registerAgent(id, newAgent);
        applyBotConfig(id, spec.getDingtalkRaw(), old.spec.getDingtalk());

        // bailian 失效缓存：buildAgent 已重新注入，旧实例引用可释放
        bailianService.invalidate(id);

        try { old.agent.close(); } catch (Exception e) {
            log.warn("Failed to close old agent '{}': {}", id, e.getMessage());
        }

        persistAll();
        log.info("Updated agent: id={}, name={}, model={}, tools={}, skills={}, dingtalk={}, bailian={}",
                spec.getId(), spec.getName(), spec.getModelName(), spec.getTools(), spec.getSkills(),
                spec.getDingtalk() != null && spec.getDingtalk().isEnabled(),
                spec.getBailian() != null && spec.getBailian().isEnabled());
        return spec;
    }

    public HarnessAgent get(String id) {
        AgentEntry entry = map.get(id);
        if (entry == null) {
            throw new NoSuchElementException("agent '" + id + "' not found");
        }
        return entry.agent;
    }

    public AgentSpec getSpec(String id) {
        AgentEntry entry = map.get(id);
        if (entry == null) {
            throw new NoSuchElementException("agent '" + id + "' not found");
        }
        return entry.spec;
    }

    public List<AgentSpec> list() {
        List<AgentSpec> specs = new ArrayList<>(map.size());
        map.values().stream()
                .sorted(Comparator.comparing(e -> e.spec.getId()))
                .forEach(e -> specs.add(e.spec));
        return specs;
    }

    public java.util.Optional<AgentSpec> findById(String id) {
        AgentEntry e = map.get(id);
        return e == null ? java.util.Optional.empty() : java.util.Optional.of(e.spec);
    }

    /** 返回指定 agent 的 workspace 根路径：workspaceRoot/<agentId>/ */
    public Path getWorkspacePath(String agentId) {
        AgentEntry e = map.get(agentId);
        if (e == null) {
            throw new NoSuchElementException("agent '" + agentId + "' not found");
        }
        return Paths.get(cfg.getWorkspaceRoot(), agentId);
    }

    public boolean delete(String id) {
        AgentEntry removed = map.remove(id);
        if (removed == null) return false;
        // 先停机器人，避免僵尸 channel
        channelRegistry.removeQuietly(id);
        // 失效 bailian 工具缓存
        bailianService.invalidate(id);
        // 清理沙箱句柄
        sandboxHandles.remove(id);
        try {
            removed.agent.close();
        } catch (Exception e) {
            log.warn("Failed to close agent '{}': {}", id, e.getMessage());
        }
        persistAll();
        log.info("Removed agent: id={}", id);
        return true;
    }

    /** BotService 调用：替换 spec.dingtalk，应用机器人配置，持久化。 */
    public void upsertDingtalk(String agentId, DingTalkBotConfig cfg) {
        AgentEntry entry = map.get(agentId);
        if (entry == null) {
            throw new NoSuchElementException("agent '" + agentId + "' not found");
        }
        DingTalkBotConfig old = entry.spec.getDingtalk();
        entry.spec.setDingtalk(cfg);
        try {
            channelRegistry.apply(agentId, cfg);
        } catch (Exception e) {
            entry.spec.setDingtalk(old);
            throw e;
        }
        persistAll();
    }

    /** BotService 调用：清除 spec.dingtalk，停机器人，持久化。 */
    public void disableDingtalk(String agentId) {
        AgentEntry entry = map.get(agentId);
        if (entry == null) {
            throw new NoSuchElementException("agent '" + agentId + "' not found");
        }
        DingTalkBotConfig old = entry.spec.getDingtalk();
        entry.spec.setDingtalk(null);
        try {
            channelRegistry.apply(agentId, null);
        } catch (Exception e) {
            entry.spec.setDingtalk(old);
            throw e;
        }
        persistAll();
    }

    /**
     * BailianRagService 调用：替换 spec.bailian，重建 HarnessAgent 注入新工具，持久化。
     * 失败时回滚 spec.bailian 到旧值。
     */
    public void upsertBailian(String agentId, BailianRagConfig cfg) {
        AgentEntry entry = map.get(agentId);
        if (entry == null) {
            throw new NoSuchElementException("agent '" + agentId + "' not found");
        }
        BailianRagConfig oldRaw = entry.spec.getBailianRaw();
        entry.spec.setBailian(cfg);
        // 重建 HarnessAgent：bailian 工具必须重新注入
        try {
            rebuildAgent(agentId);
        } catch (RuntimeException e) {
            entry.spec.setBailian(oldRaw);
            throw e;
        }
        persistAll();
    }

    /** BailianRagService 调用：清除 spec.bailian，重建 HarnessAgent 移除工具，持久化。 */
    public void disableBailian(String agentId) {
        AgentEntry entry = map.get(agentId);
        if (entry == null) {
            throw new NoSuchElementException("agent '" + agentId + "' not found");
        }
        BailianRagConfig oldRaw = entry.spec.getBailianRaw();
        entry.spec.setBailian(null);
        try {
            rebuildAgent(agentId);
        } catch (RuntimeException e) {
            entry.spec.setBailian(oldRaw);
            throw e;
        }
        persistAll();
    }

    /**
     * 用当前内存中的 spec 重建 HarnessAgent 并替换注册表。
     * bailian 变更必须重建（工具是 build 阶段注入的）。
     */
    private void rebuildAgent(String agentId) {
        AgentEntry old = map.get(agentId);
        if (old == null) return;
        // 重建前先清掉旧句柄，避免并发请求持有过期 fsSpec 引用
        sandboxHandles.remove(agentId);
        HarnessAgent newAgent = buildAgent(old.spec);
        AgentEntry newEntry = new AgentEntry(old.spec, old.model, newAgent);
        map.put(agentId, newEntry);
        channelRegistry.registerAgent(agentId, newAgent);
        bailianService.invalidate(agentId);
        try {
            old.agent.close();
        } catch (Exception e) {
            log.warn("Failed to close old agent during rebuild '{}': {}", agentId, e.getMessage());
        }
        log.info("Rebuilt agent '{}' (bailian changed)", agentId);
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down AgentRegistry, closing {} agents", map.size());
        for (AgentEntry entry : map.values()) {
            try { entry.agent.close(); } catch (Exception e) {
                log.warn("close() failed for agent '{}': {}", entry.spec.getId(), e.getMessage());
            }
        }
        map.clear();
        bailianService.invalidateAll();
    }

    /** 启动时从持久化文件加载已有 Agent，并批量注入 Gateway。失败的单条不影响其他。 */
    private void loadFromPersistence() {
        List<AgentSpec> specs = persistence.load();
        List<HarnessAgent> seeds = new ArrayList<>();
        List<String> seedIds = new ArrayList<>();
        int ok = 0, fail = 0;
        for (AgentSpec spec : specs) {
            try {
                HarnessAgent agent = buildAgent(spec);
                seeds.add(agent);
                seedIds.add(spec.getId());
                AgentEntry entry = new AgentEntry(spec, modelFactory.getOrBuild(spec.getModelName()), agent);
                AgentEntry existing = map.putIfAbsent(spec.getId(), entry);
                if (existing != null) {
                    try { agent.close(); } catch (Exception ignored) {}
                    continue;
                }
                ok++;
            } catch (Exception e) {
                fail++;
                log.error("Skip persisted agent '{}': {}", spec.getId(), e.getMessage());
            }
        }
        // 初始化 GatewayBootstrap 种子。持久化为空 → stub 占位
        if (!seeds.isEmpty()) {
            channelRegistry.initialize(seeds, seedIds);
        } else {
            channelRegistry.initializeWithStub();
        }
        // 注册到 gateway.agentRegistry（HarnessGateway.build 阶段已注册 main，其余 registerAgent）
        for (AgentEntry e : map.values()) {
            channelRegistry.registerAgent(e.spec.getId(), e.agent);
            applyBotConfig(e.spec.getId(), e.spec.getDingtalkRaw(), null);
        }
        log.info("Persistence load finished: {} ok, {} failed", ok, fail);
    }

    /**
     * 仅写入内存（含 Gateway 注册 + 应用机器人配置），不写盘。
     * 启动加载期间使用，避免重复写盘。
     */
    private void registerInternal(AgentSpec spec) {
        HarnessAgent agent = buildAgent(spec);
        Model model = modelFactory.getOrBuild(spec.getModelName());
        AgentEntry entry = new AgentEntry(spec, model, agent);
        AgentEntry existing = map.putIfAbsent(spec.getId(), entry);
        if (existing != null) {
            try { agent.close(); } catch (Exception ignored) {}
            return;
        }
        channelRegistry.registerAgent(spec.getId(), agent);
        applyBotConfig(spec.getId(), spec.getDingtalkRaw(), null);
    }

    /**
     * 根据 spec 构建 HarnessAgent：可选注入 ToolsConfig allow 白名单与 SkillFilter 白名单。
     * 若 spec.bailian 启用且合并全局默认值后字段完整，额外往 Toolkit 注册 retrieve_knowledge 工具。
     *
     * <p>启用 {@code enablePendingToolRecovery(true)}：当 SSE 流中断导致上一轮的
     * ToolUseBlock 没拿到对应 ToolResultBlock 时，下一次 call() 框架会自动补一个合成的 error
     * result 进去，agent 不会因为「pending tool calls exist without results」而崩溃。
     */
    private HarnessAgent buildAgent(AgentSpec spec) {
        Path hostWorkspaceRoot = Paths.get(cfg.getWorkspaceRoot(), spec.getId());
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(spec.getName())
                // 显式绑定 id 作 harness 的 agent namespace key。
                // 不设置时 harness 会 fallback 到 name，而 name 可改、可重名——会引发沙箱 state 键漂移
                // （已踩坑：state 目录与 slotSid 一致性破坏 → 文件浏览看到的是另一台容器）。
                // 这里统一用 spec.getId()，与 workspace/.agentscope/workspace/<id>/ 同一来源。
                .agentId(spec.getId())
                .sysPrompt(spec.getSysPrompt())
                .workspace(hostWorkspaceRoot)
                .model(modelFactory.getOrBuild(spec.getModelName()))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(cfg.getTriggerMessages())
                        .keepMessages(cfg.getKeepMessages())
                        .build())
                .enablePendingToolRecovery(true);

        // 全局 skill 仓库（始终注册），最低优先级
        if (java.nio.file.Files.isDirectory(skillsDir)) {
            builder.projectGlobalSkillsDir(skillsDir);
        }

        List<String> tools = spec.getTools();
        if (tools != null && !tools.isEmpty()) {
            ToolsConfig toolsConfig = new ToolsConfig();
            toolsConfig.setAllow(new ArrayList<>(tools));
            builder.toolsConfig(toolsConfig);
        }

        List<String> skills = spec.getSkills();
        if (skills != null && !skills.isEmpty()) {
            builder.skillFilter(SkillFilter.only(skills.toArray(new String[0])));
        } else {
            builder.skillFilter(SkillFilter.all());
        }

        // fsSpec 同时绑定到 agent 和 sandboxHandles，供 SandboxFileService 直接复用
        DockerFilesystemSpec fsSpec = buildSandboxFilesystemSpec();
        builder.filesystem(fsSpec.isolationScope(IsolationScope.USER));
        HarnessAgent agent = builder.build();

        // 缓存沙箱句柄（fsSpec + 宿主 workspaceRoot）供沙箱文件浏览控制器使用
        sandboxHandles.put(spec.getId(), new SandboxHandle(spec.getId(), fsSpec, hostWorkspaceRoot));

        // bailian：build 之后往 HarnessAgent 已有的 Toolkit 注入 retrieve_knowledge
        // 这样不会丢失 harness 内置工具（filesystem/shell/memory/plan/skills）
        BailianRagConfig effectiveBailian = bailianService.mergeWithGlobal(spec.getBailianRaw());
        if (effectiveBailian != null && effectiveBailian.isEnabled() && effectiveBailian.isComplete()) {
            try {
                BailianKnowledgeTool ragTool = bailianService.buildTools(spec.getId(), effectiveBailian);
                if (ragTool != null) {
                    // Toolkit.registerTool(Object) 扫描 @Tool 注解方法注册；自实现工具不依赖 v1 KnowledgeRetrievalTools
                    agent.getToolkit().registerTool(ragTool);
                    log.info("Attached retrieve_knowledge to agent '{}' (index={})", spec.getId(), effectiveBailian.getIndexId());
                }
            } catch (Exception e) {
                // 明确告警：agent 配置里勾了 RAG 但工具没注入（常见原因是凭证错、endpoint 空、AK 无效等）
                String causeMsg = e.getCause() != null ? e.getCause().getMessage() : "n/a";
                log.warn("Bailian RAG tool NOT attached to agent '{}' (enabled=true but client build failed: {}; cause={}). "
                        + "Agent will run WITHOUT retrieve_knowledge.",
                        spec.getId(), e.getMessage(), causeMsg);
            }
        }

        return agent;
    }

    /**
     * 构造沙箱文件系统 spec：保持与 buildAgent 内部原先写法一致（image / memory / cpu / network /
     * hardening args / snapshot）。
     * <p>抽出来供 sandboxHandles 复用——这样 SandboxFileService 不用重新解析配置就能拿到
     * 与 agent 同一份 KeepAliveDockerSandboxClient 包装的 fsSpec。
     */
    private DockerFilesystemSpec buildSandboxFilesystemSpec() {
        DockerFilesystemSpec fsSpec = new DockerFilesystemSpec()
                .client(new KeepAliveDockerSandboxClient(sandboxKeepAliveManager))
                .image(sandboxCfg.getImage() != null && !sandboxCfg.getImage().isBlank()
                        ? sandboxCfg.getImage() : "untitled-sandbox:latest")
                .memorySizeBytes(parseMemoryToBytes(sandboxCfg.getMemory()))
                .cpuCount(sandboxCfg.getCpu() != null ? sandboxCfg.getCpu().longValue() : null)
                .network(sandboxCfg.getNetwork() != null && !sandboxCfg.getNetwork().isBlank()
                        ? sandboxCfg.getNetwork() : "bridge")
                .additionalRunArgs(buildSandboxAdditionalRunArgs());
        String snapshotRoot = expandHome(sandboxCfg.getSnapshotRoot());
        if (snapshotRoot != null) {
            fsSpec.snapshotSpec(new LocalSnapshotSpec(snapshotRoot));
        }
        return fsSpec;
    }

    /**
     * 把 "4g"/"512m"/"1024k" 这样的 docker 内存字符串解析为字节数。
     * 解析失败或为空返回 null（Docker 会沿用宿主机默认；不强制）。
     */
    static Long parseMemoryToBytes(String memory) {
        if (memory == null) return null;
        String s = memory.trim().toLowerCase();
        if (s.isEmpty()) return null;
        long multiplier;
        if (s.endsWith("g")) { multiplier = 1024L * 1024 * 1024; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { multiplier = 1024L * 1024; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("k")) { multiplier = 1024L; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { multiplier = 1L; s = s.substring(0, s.length() - 1); }
        else { multiplier = 1L; }
        try {
            long n = Long.parseLong(s.trim());
            if (n <= 0) return null;
            return n * multiplier;
        } catch (NumberFormatException e) {
            log.warn("Invalid sandbox memory '{}', falling back to docker default", memory);
            return null;
        }
    }

    /**
     * 展开路径前缀 ~ 为 user.home。null/空/纯空白返回 null（表示未配置）。
     */
    static String expandHome(String path) {
        if (path == null) return null;
        String s = path.trim();
        if (s.isEmpty()) return null;
        if (s.equals("~")) return System.getProperty("user.home");
        if (s.startsWith("~/")) return System.getProperty("user.home") + s.substring(1);
        return s;
    }

    /**
     * 构造 sandbox 容器的额外 docker run 参数。
     * 包含 hardening（cap-drop / no-new-privileges / tmpfs /tmp）
     * + dws 容器内独立登录态（DWS_CONFIG_DIR=/workspace, DWS_KEYCHAIN_DIR=/workspace/.dws/keychain）
     * + DWS_DISABLE_KEYCHAIN=1（Linux 容器无 macOS Keychain，强制 file-DEK 解密）。
     */
    private List<String> buildSandboxAdditionalRunArgs() {
        List<String> args = new ArrayList<>();
        // hardening：--cap-drop=ALL 丢掉所有 capabilities，但 setgroups()/setuid()
        // 需要 CAP_SETGID+CAP_SETUID（dpkg/apt 装包、某些 shell 工具初始化时调用）。
        // 单独加回这两个最小权限，网络/挂载/内核相关危险 capability 仍被丢弃。
        args.add("--cap-drop=ALL");
        args.add("--cap-add=SETGID");
        args.add("--cap-add=SETUID");
        args.add("--security-opt=no-new-privileges:true");
        args.add("--tmpfs");
        args.add("/tmp:size=512m,mode=1777");

        // dws 容器内独立登录态：配置直接放 workspace 根目录，加密凭证放 .dws/keychain 子目录。
        // 不再 bind-mount 宿主凭证 —— 每个容器有自己的登录态，由快照持久化。
        // Linux 容器无 macOS Keychain，强制 file-DEK 解密。
        args.add("-e");
        args.add("DWS_CONFIG_DIR=/workspace");
        args.add("-e");
        args.add("DWS_KEYCHAIN_DIR=/workspace/.dws/keychain");
        args.add("-e");
        args.add("DWS_DISABLE_KEYCHAIN=1");
        // 容器时区：中国上海
        args.add("-e");
        args.add("TZ=Asia/Shanghai");
        return args;
    }

    /** 应用机器人配置；newCfg==null 表示停用；保留 oldCfg 用于失败回滚。 */
    private void applyBotConfig(String agentId, DingTalkBotConfig newCfg, DingTalkBotConfig oldCfg) {
        try {
            channelRegistry.apply(agentId, newCfg);
        } catch (Exception e) {
            log.warn("Apply dingtalk for agent '{}' failed: {}", agentId, e.getMessage());
            // 不抛：上层（create/update）的 spec 字段已写入，等用户单独修
        }
    }

    /** 加锁全量落盘。失败仅 WARN，不抛异常（避免阻塞 HTTP 调用）。 */
    public void persistAll() {
        writeLock.lock();
        try {
            persistence.save(list());
        } catch (Exception e) {
            log.warn("Failed to persist agents: {}", e.getMessage());
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * 沙箱文件浏览所需的 agent 级句柄：复用 buildAgent 阶段构造的 fsSpec 与宿主 workspace 根，
     * 避免 SandboxFileService 重新解析配置（image / memory / cpu / network / snapshot）。
     *
     * <p>fsSpec 内嵌 KeepAliveDockerSandboxClient（与 agent 共用同一个 SandboxKeepAliveManager），
     * 因此容器空闲延迟销毁、并发复用同一容器的语义跟对话路径完全一致。
     */
    public record SandboxHandle(String agentId,
                               io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec fsSpec,
                               java.nio.file.Path hostWorkspaceRoot) {
    }
}