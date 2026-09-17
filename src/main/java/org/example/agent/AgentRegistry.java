package org.example.agent;

import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import jakarta.annotation.PreDestroy;
import org.example.bot.DingTalkBotConfig;
import org.example.bot.DingTalkChannelRegistry;
import org.example.config.AgentProperties;
import org.example.config.ModelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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
    private final Path skillsDir;
    private final AgentPersistence persistence;
    private final DingTalkChannelRegistry channelRegistry;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ConcurrentHashMap<String, AgentEntry> map = new ConcurrentHashMap<>();

    public AgentRegistry(ModelFactory modelFactory,
                        AgentProperties props,
                        AgentPersistence persistence,
                        DingTalkChannelRegistry channelRegistry) {
        this.modelFactory = modelFactory;
        this.cfg = props.getOpenai();
        this.globalCfg = props.getConfig();
        this.skillsDir = Paths.get(globalCfg.getSkillsDir()).toAbsolutePath().normalize();
        this.persistence = persistence;
        this.channelRegistry = channelRegistry;
        loadFromPersistence();
    }

    public AgentSpec create(AgentSpec spec) {
        // 校验 dingtalk：enabled=true 时三个字段必填；任一缺失提前抛错，避免创建后 channel 启动失败
        DingTalkBotConfig bot = spec.getDingtalkRaw();
        if (bot != null && bot.isEnabled() && !bot.isComplete()) {
            throw new IllegalArgumentException(
                    "dingtalk 配置不完整：appKey / appSecret / robotCode 必填");
        }

        HarnessAgent agent = buildAgent(spec);
        AgentEntry entry = new AgentEntry(spec, modelFactory.getOrBuild(spec.getModelName()), agent);
        AgentEntry existing = map.putIfAbsent(spec.getId(), entry);
        if (existing != null) {
            try { agent.close(); } catch (Exception ignored) {}
            throw new IllegalStateException("agent '" + spec.getId() + "' already exists");
        }

        channelRegistry.registerAgent(spec.getId(), agent);
        applyBotConfig(spec.getId(), spec.getDingtalk(), null);

        persistAll();
        log.info("Registered agent: id={}, name={}, model={}, tools={}, skills={}, dingtalk={}",
                spec.getId(), spec.getName(), spec.getModelName(), spec.getTools(), spec.getSkills(),
                spec.getDingtalk() != null && spec.getDingtalk().isEnabled());
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

        HarnessAgent newAgent = buildAgent(spec);
        Model newModel = modelFactory.getOrBuild(spec.getModelName());
        AgentEntry newEntry = new AgentEntry(spec, newModel, newAgent);
        map.put(id, newEntry);

        channelRegistry.registerAgent(id, newAgent);
        applyBotConfig(id, spec.getDingtalk(), old.spec.getDingtalk());

        try { old.agent.close(); } catch (Exception e) {
            log.warn("Failed to close old agent '{}': {}", id, e.getMessage());
        }

        persistAll();
        log.info("Updated agent: id={}, name={}, model={}, tools={}, skills={}, dingtalk={}",
                spec.getId(), spec.getName(), spec.getModelName(), spec.getTools(), spec.getSkills(),
                spec.getDingtalk() != null && spec.getDingtalk().isEnabled());
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

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down AgentRegistry, closing {} agents", map.size());
        for (AgentEntry entry : map.values()) {
            try { entry.agent.close(); } catch (Exception e) {
                log.warn("close() failed for agent '{}': {}", entry.spec.getId(), e.getMessage());
            }
        }
        map.clear();
    }

    /** 启动时从持久化文件加载已有 Agent，并批量注入 Gateway。失败的单条不影响其他。 */
    private void loadFromPersistence() {
        List<AgentSpec> specs = persistence.load();
        List<HarnessAgent> seeds = new ArrayList<>();
        int ok = 0, fail = 0;
        for (AgentSpec spec : specs) {
            try {
                HarnessAgent agent = buildAgent(spec);
                seeds.add(agent);
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
            channelRegistry.initialize(seeds);
        } else {
            channelRegistry.initializeWithStub();
        }
        // 注册到 gateway.agentRegistry（HarnessGateway.build 阶段已注册 main，其余 registerAgent）
        for (AgentEntry e : map.values()) {
            channelRegistry.registerAgent(e.spec.getId(), e.agent);
            applyBotConfig(e.spec.getId(), e.spec.getDingtalk(), null);
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
        applyBotConfig(spec.getId(), spec.getDingtalk(), null);
    }

    /** 根据 spec 构建 HarnessAgent，可选注入 ToolsConfig allow 白名单与 SkillFilter 白名单。 */
    private HarnessAgent buildAgent(AgentSpec spec) {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .name(spec.getName())
                .sysPrompt(spec.getSysPrompt())
                .workspace(Paths.get(cfg.getWorkspaceRoot(), spec.getId()))
                .model(modelFactory.getOrBuild(spec.getModelName()))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(cfg.getTriggerMessages())
                        .keepMessages(cfg.getKeepMessages())
                        .build());

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

        return builder.build();
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
}