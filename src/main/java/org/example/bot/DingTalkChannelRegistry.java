package org.example.bot;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.channel.dingtalk.DingTalkChannel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.ChannelManager;
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 钉钉机器人 Channel 注册中心。
 *
 * <p>负责：
 * <ul>
 *   <li>维护单例 {@link GatewayBootstrap} + {@link ChannelManager}</li>
 *   <li>把 Agent 注册进 Gateway（registerAgent）</li>
 *   <li>按 agentId 动态挂载/卸载 DingTalkChannel（启用即 start，停用即 stop）</li>
 * </ul>
 *
 * <p>Channel id 约定为 {@code dingtalk-<agentId>}；每个 agent 至多 1 个钉钉机器人。
 *
 * <p>GatewayBootstrap 强制要求至少一个 agent。本类用两阶段构造：
 * <ol>
 *   <li>Spring 启动时无参，{@link #bootstrap} 为 null</li>
 *   <li>{@link AgentRegistry} 加载完持久化 agent 后调 {@link #initialize(List)}
 *       （持久化为空时，调 {@link #initializeWithStub()} 建一个 OpenAI 占位 stub）</li>
 * </ol>
 *
 * <p>所有 public 操作（registerAgent / apply / removeQuietly 等）都会先 lazy ensureInitialized，
 * 因此从 Spring 视角看本类无外部依赖。
 */
@Component
public class DingTalkChannelRegistry {

    private static final Logger log = LoggerFactory.getLogger(DingTalkChannelRegistry.class);

    public static final String CHANNEL_PREFIX = "dingtalk-";

    private volatile GatewayBootstrap bootstrap;
    private volatile ChannelManager channelManager;
    private final ReentrantLock lock = new ReentrantLock();
    /** channelId → 当前生效的 DingTalk 配置（用于查询当前状态） */
    private final Map<String, DingTalkBotConfig> activeConfigs = new ConcurrentHashMap<>();

    public DingTalkChannelRegistry() {
    }

    /**
     * 用真实 seeds 初始化 GatewayBootstrap。已初始化则 no-op。
     * seeds 为空请改调 {@link #initializeWithStub()}。
     */
    public synchronized void initialize(List<HarnessAgent> seeds) {
        Objects.requireNonNull(seeds, "seeds");
        if (bootstrap != null) {
            log.info("DingTalkChannelRegistry already initialized, re-init is a no-op");
            return;
        }
        if (seeds.isEmpty()) {
            throw new IllegalArgumentException("seeds 为空，请调 initializeWithStub()");
        }
        build(seeds);
    }

    /** 持久化为空时初始化：构造一个永不路由的占位 stub 作 main。 */
    public synchronized void initializeWithStub() {
        if (bootstrap != null) return;
        build(List.of(buildStubAgent()));
    }

    private void build(List<HarnessAgent> seeds) {
        GatewayBootstrap.Builder b = GatewayBootstrap.builder();
        HarnessAgent main = seeds.get(0);
        b.agent(main.getAgentId(), main).mainAgent(main.getAgentId());
        for (int i = 1; i < seeds.size(); i++) {
            HarnessAgent a = seeds.get(i);
            b.agent(a.getAgentId(), a);
        }
        this.bootstrap = b.build();
        this.channelManager = bootstrap.channelManager();
        log.info("DingTalkChannelRegistry initialized with {} seed agents (main='{}')",
                seeds.size(), main.getAgentId());
    }

    private static HarnessAgent buildStubAgent() {
        Model stubModel = OpenAIChatModel.builder()
                .baseUrl("http://127.0.0.1:1")
                .apiKey("__bootstrap_stub__")
                .modelName("__bootstrap_stub__")
                .build();
        return HarnessAgent.builder()
                .name("__bootstrap_stub__")
                .sysPrompt("noop")
                .model(stubModel)
                .build();
    }

    public boolean isInitialized() {
        return bootstrap != null;
    }

    private synchronized void ensureInitializedWithStub() {
        if (bootstrap != null) return;
        log.warn("Lazy init GatewayBootstrap with stub agent (main) — first real agent will be added via registerAgent");
        initializeWithStub();
    }

    public GatewayBootstrap bootstrap() {
        ensureInitializedWithStub();
        return bootstrap;
    }

    public io.agentscope.harness.agent.gateway.HarnessGateway gateway() {
        return (io.agentscope.harness.agent.gateway.HarnessGateway) bootstrap().gateway();
    }

    /** 注册或替换指定 agent。put 语义，引用已存在则覆盖。 */
    public void registerAgent(String agentId, HarnessAgent agent) {
        gateway().registerAgent(agentId, agent);
    }

    public ChannelManager channelManager() {
        return bootstrap().channelManager();
    }

    /**
     * 应用某个 agent 的机器人配置：
     * <ul>
     *   <li>{@code cfg == null} 或 {@code enabled=false}：停掉并卸载该 channel</li>
     *   <li>{@code cfg.enabled=true} 且字段齐：构建新 channel 并 start（覆盖已有同 id 的）</li>
     *   <li>字段不全：抛 IllegalArgumentException，旧的 channel 也会被先停掉</li>
     * </ul>
     */
    public void apply(String agentId, DingTalkBotConfig cfg) {
        String channelId = channelId(agentId);
        ensureInitializedWithStub();
        lock.lock();
        try {
            if (channelManager.getChannel(channelId).isPresent()) {
                channelManager.unregister(channelId);
                activeConfigs.remove(channelId);
            }

            if (cfg == null || !cfg.isEnabled()) {
                log.info("DingTalk channel '{}' disabled (agent='{}')", channelId, agentId);
                return;
            }
            if (!cfg.isComplete()) {
                throw new IllegalArgumentException(
                        "dingtalk 配置不完整：appKey / appSecret / robotCode 必填");
            }

            ChannelConfig routing = ChannelConfig.builder(channelId)
                    .defaultAgentId(agentId)
                    .build();
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("appKey", cfg.getAppKey());
            raw.put("appSecret", cfg.getAppSecret());
            raw.put("robotCode", cfg.getRobotCode());

            Channel channel = DingTalkChannel.fromProperties(channelId, routing, raw);
            channelManager.register(channel);
            try {
                channel.init(bootstrap.gateway());
                channel.start();
            } catch (Exception e) {
                channelManager.unregister(channelId);
                throw e;
            }
            activeConfigs.put(channelId, cfg);
            log.info("DingTalk channel '{}' started (agent='{}')", channelId, agentId);
        } finally {
            lock.unlock();
        }
    }

    /** 仅卸载 channel，不抛异常。用于 agent 删除/重建前的清理。 */
    public void removeQuietly(String agentId) {
        if (!isInitialized()) return;
        try {
            apply(agentId, null);
        } catch (Exception e) {
            log.warn("removeQuietly failed for agent '{}': {}", agentId, e.getMessage());
        }
    }

    public boolean isActive(String agentId) {
        if (!isInitialized()) return false;
        return channelManager.getChannel(channelId(agentId)).isPresent();
    }

    public DingTalkBotConfig currentConfig(String agentId) {
        return activeConfigs.get(channelId(agentId));
    }

    public Collection<String> activeAgentIds() {
        return activeConfigs.keySet().stream()
                .map(DingTalkChannelRegistry::agentIdFromChannel)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public static String channelId(String agentId) {
        return CHANNEL_PREFIX + agentId;
    }

    public static String agentIdFromChannel(String channelId) {
        if (channelId == null || !channelId.startsWith(CHANNEL_PREFIX)) return null;
        return channelId.substring(CHANNEL_PREFIX.length());
    }

    @PreDestroy
    public void shutdown() {
        if (!isInitialized()) return;
        log.info("DingTalkChannelRegistry shutting down: {} active channels", activeConfigs.size());
        channelManager.stopAll();
        activeConfigs.clear();
    }
}