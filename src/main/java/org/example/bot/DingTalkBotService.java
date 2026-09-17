package org.example.bot;

import org.example.agent.AgentRegistry;
import org.example.agent.AgentSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 钉钉机器人业务层。
 *
 * <p>职责：
 * <ul>
 *   <li>GET —— 返回脱敏后的 {@link DingTalkBotConfig}（appSecret 永远是 *** 或 null）</li>
 *   <li>PUT —— 校验 + 合并（保留未改动的 appSecret） + 转发给 {@link AgentRegistry}</li>
 *   <li>DELETE —— 转发给 {@link AgentRegistry#disableDingtalk}</li>
 * </ul>
 */
@Service
public class DingTalkBotService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkBotService.class);

    private final AgentRegistry agentRegistry;

    public DingTalkBotService(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    /**
     * 返回 agent 当前机器人配置。脱敏：appSecret 一律显示为 "***"。
     * spec 里没有 dingtalk 字段时返回 enabled=false 的空壳。
     */
    public DingTalkBotConfig get(String agentId) {
        AgentSpec spec = agentRegistry.getSpec(agentId);
        DingTalkBotConfig raw = spec.getDingtalkRaw();
        if (raw == null) {
            DingTalkBotConfig empty = new DingTalkBotConfig();
            empty.setEnabled(false);
            return empty;
        }
        return raw.masked();
    }

    /**
     * 启/改机器人配置。返回最终生效的配置（脱敏）。
     * 如果传入的 {@code input.enabled=false}，等同于 disable。
     */
    public DingTalkBotConfig upsert(String agentId, DingTalkBotConfig input) {
        if (input == null) {
            throw new IllegalArgumentException("body 不能为空");
        }
        DingTalkBotConfig resolved;
        if (!input.isEnabled()) {
            resolved = null;
        } else {
            DingTalkBotConfig old = agentRegistry.getSpec(agentId).getDingtalkRaw();
            resolved = merge(old, input);
            if (!resolved.isComplete()) {
                throw new IllegalArgumentException(
                        "dingtalk 配置不完整：appKey / appSecret / robotCode 必填");
            }
        }
        agentRegistry.upsertDingtalk(agentId, resolved);
        log.info("Agent '{}' dingtalk upserted: enabled={}", agentId, input.isEnabled());
        return get(agentId);
    }

    /** 停机器人并清除 spec.dingtalk。 */
    public boolean disable(String agentId) {
        boolean existed = agentRegistry.getSpec(agentId).getDingtalkRaw() != null;
        if (!existed) return false;
        agentRegistry.disableDingtalk(agentId);
        log.info("agent '{}' dingtalk disabled", agentId);
        return true;
    }

    /** 合并旧配置与新输入：secret 字段若传 "***" 或为空，则保留旧值。 */
    private static DingTalkBotConfig merge(DingTalkBotConfig old, DingTalkBotConfig input) {
        String secret = input.getAppSecret();
        if ((secret == null || secret.isBlank() || DingTalkBotConfig.MASKED.equals(secret))
                && old != null) {
            secret = old.getAppSecret();
        }
        DingTalkBotConfig out = new DingTalkBotConfig();
        out.setEnabled(true);
        out.setAppKey(emptyOrKeep(input.getAppKey(), old != null ? old.getAppKey() : null));
        out.setAppSecret(secret);
        out.setRobotCode(emptyOrKeep(input.getRobotCode(), old != null ? old.getRobotCode() : null));
        return out;
    }

    private static String emptyOrKeep(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}