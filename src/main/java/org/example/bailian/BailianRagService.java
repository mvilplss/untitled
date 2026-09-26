package org.example.bailian;

import io.agentscope.core.rag.integration.bailian.BailianClient;
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.integration.bailian.RerankConfig;
import io.agentscope.core.rag.integration.bailian.RewriteConfig;
import org.example.agent.AgentRegistry;
import org.example.agent.AgentSpec;
import org.example.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 阿里云百炼 RAG 业务层 + 工具构造器。
 *
 * <p>职责：
 * <ul>
 *   <li>GET —— 返回脱敏后的 {@link BailianRagConfig}（accessKeySecret 永远是 *** 或 null）</li>
 *   <li>PUT —— 校验 + 合并（保留未改动的 accessKeySecret） + 转发给 {@link AgentRegistry} 重建 HarnessAgent</li>
 *   <li>DELETE —— 转发给 {@link AgentRegistry#disableBailian}</li>
 *   <li>把 AgentSpec.bailian + 全局 AgentProperties.Bailian 合并成 {@link BailianConfig}</li>
 *   <li>用配置构造自实现的 {@link BailianKnowledgeTool} 缓存（完全脱离 v1 RAG API）</li>
 * </ul>
 *
 * <p>合并规则：per-agent 字段优先；为空时回退到全局配置（accessKeyId/accessKeySecret/endpoint）；
 * workspaceId/indexId 必须 per-agent 提供（全局不存）。
 */
@Service
public class BailianRagService {

    private static final Logger log = LoggerFactory.getLogger(BailianRagService.class);

    private final AgentRegistry agentRegistry;
    private final AgentProperties.Bailian global;
    /** agentId → 缓存的自实现 retrieve_knowledge 工具实例（按 spec 重置/停用时 invalidate） */
    private final ConcurrentHashMap<String, BailianKnowledgeTool> toolCache = new ConcurrentHashMap<>();

    public BailianRagService(@Lazy AgentRegistry agentRegistry, AgentProperties props) {
        this.agentRegistry = agentRegistry;
        this.global = props.getBailian();
    }

    // ===== Controller-facing API =====

    /**
     * 返回 agent 当前 RAG 配置。脱敏：accessKeySecret 一律显示为 "***"。
     * spec 里没有 bailian 字段时返回 enabled=false 的空壳。
     */
    public BailianRagConfig get(String agentId) {
        AgentSpec spec = agentRegistry.getSpec(agentId);
        BailianRagConfig raw = spec.getBailianRaw();
        if (raw == null) {
            BailianRagConfig empty = new BailianRagConfig();
            empty.setEnabled(false);
            return empty;
        }
        return raw.masked();
    }

    /**
     * 启/改 RAG 配置。返回最终生效的配置（脱敏）。
     * 如果传入的 {@code input.enabled=false}，等同于 disable。
     */
    public BailianRagConfig upsert(String agentId, BailianRagConfig input) {
        if (input == null) {
            throw new IllegalArgumentException("body 不能为空");
        }
        BailianRagConfig resolved;
        if (!input.isEnabled()) {
            resolved = null;
        } else {
            BailianRagConfig old = agentRegistry.getSpec(agentId).getBailianRaw();
            resolved = merge(old, input);
            if (!resolved.isComplete()) {
                throw new IllegalArgumentException(
                        "bailian 配置不完整：accessKeyId / accessKeySecret / workspaceId / indexId 必填（未填字段可走 application.yml 全局默认）");
            }
            // 拒绝明显是占位符的 AK/SK：避免发起必败的 retrieve 调用产生误导性日志
            // （服务端会返回 400 "Specified signature does not match our calculation"，根因其实是 SK 不对）
            String placeholderDetail = findPlaceholderCredentials(resolved);
            if (placeholderDetail != null) {
                throw new IllegalArgumentException(placeholderDetail);
            }
        }
        agentRegistry.upsertBailian(agentId, resolved);
        log.info("Agent '{}' bailian upserted: enabled={}", agentId, input.isEnabled());
        return get(agentId);
    }

    /**
     * 检测 accessKeyId / accessKeySecret 是否是「一眼假」的占位符。
     * 返回 null 表示看起来 OK；返回 String 给出明确报错。
     */
    private String findPlaceholderCredentials(BailianRagConfig cfg) {
        String ak = cfg.getAccessKeyId();
        String sk = cfg.getAccessKeySecret();
        if (looksLikePlaceholder(ak, "accessKeyId")) {
            return "bailian accessKeyId 看起来是占位符（"
                    + ak + "），请填入阿里云控制台真实 AccessKey ID。"
                    + "如果 SK 用了 REPLACE_ME 或类似占位符也会被同样拒绝。";
        }
        if (looksLikePlaceholder(sk, "accessKeySecret")) {
            return "bailian accessKeySecret 看起来是占位符（"
                    + sk.substring(0, Math.min(sk.length(), 12))
                    + "...），请填入阿里云控制台真实 AccessKey Secret。"
                    + "提示：常见的假值 'REPLACE_ME' / 'YOUR_KEY_HERE' / 'xxxxx' 会被拦截。";
        }
        return null;
    }

    private static boolean looksLikePlaceholder(String value, String fieldName) {
        if (value == null || value.isBlank()) return false; // 空值由 isComplete() 拦
        String v = value.trim();
        String low = v.toLowerCase();
        // 关键词命中
        if (low.contains("replace") || low.contains("your_") || low.contains("your-")
                || low.contains("xxxx") || low.contains("xxxxxx") || low.contains("<")
                || low.contains("todo") || low.contains("placeholder")
                || low.contains("sample") || low.contains("dummy")
                || low.contains("test123") || low.contains("fake")) {
            return true;
        }
        // 单一字符重复（"aaaaa", "11111"）
        char first = v.charAt(0);
        boolean allSame = true;
        for (int i = 1; i < v.length(); i++) {
            if (v.charAt(i) != first) { allSame = false; break; }
        }
        if (allSame && v.length() >= 6) return true;
        // 太短（真 SK 至少 30 字符，真 AK 至少 16 字符 LTAI...）
        if (fieldName.equals("accessKeyId") && v.length() < 16) return true;
        if (fieldName.equals("accessKeySecret") && v.length() < 20) return true;
        return false;
    }

    /** 停用 RAG 并清除 spec.bailian。 */
    public boolean disable(String agentId) {
        boolean existed = agentRegistry.getSpec(agentId).getBailianRaw() != null;
        if (!existed) return false;
        agentRegistry.disableBailian(agentId);
        log.info("agent '{}' bailian disabled", agentId);
        return true;
    }

    // ===== Tool construction API (called by AgentRegistry.buildAgent) =====

    /**
     * 把 BailianRagConfig（已合并全局默认值）构造成 {@link BailianKnowledgeTool}。
     * 入参要求 enabled=true 且 isComplete=true；否则返回 null。
     * 同 agentId 重复调用会复用缓存（配置变更后由调用方显式 invalidate）。
     *
     * <p>实现路径：构造 {@link BailianKnowledge} 仅用于拿到 {@link BailianClient}（getClient
     * 不是 v1 {@code Knowledge} 接口方法），后续检索直接调 {@link BailianClient#retrieve}，
     * 完全绕开 v1 RAG API（{@code Knowledge.retrieve} / {@code KnowledgeRetrievalTools}）。
     */
    public BailianKnowledgeTool buildTools(String agentId, BailianRagConfig cfg) {
        if (cfg == null || !cfg.isEnabled() || !cfg.isComplete()) return null;
        return toolCache.computeIfAbsent(agentId, id -> {
            BailianConfig bailianCfg = buildConfig(cfg);
            // 构造 BailianKnowledge 仅为了拿到 BailianClient；getClient() 不是 v1 Knowledge 接口方法
            BailianKnowledge knowledge = BailianKnowledge.builder().config(bailianCfg).build();
            BailianClient client = knowledge.getClient();
            int topK = cfg.getLimit() != null ? cfg.getLimit() : 5;
            double threshold = cfg.getScoreThreshold() != null ? cfg.getScoreThreshold() : 0.0;
            String resolvedEndpoint = bailianCfg.getEndpoint();
            log.info("Built BailianKnowledgeTool for agent '{}': workspace={}, index={}, endpoint={}, topK={}, threshold={}, rerank={}, rewrite={}",
                    agentId, client.getWorkspaceId(), cfg.getIndexId(),
                    resolvedEndpoint != null ? resolvedEndpoint : "(default bailian.cn-beijing.aliyuncs.com)",
                    topK, threshold,
                    Boolean.TRUE.equals(cfg.getEnableRerank()), Boolean.TRUE.equals(cfg.getEnableRewrite()));
            return new BailianKnowledgeTool(client, cfg.getIndexId(), topK, threshold);
        });
    }

    /** 把 BailianRagConfig + 全局配置合并成完整的 BailianConfig。 */
    BailianConfig buildConfig(BailianRagConfig cfg) {
        String endpoint = resolve(cfg.getEndpoint(), global.getEndpoint());
        BailianConfig.Builder b = BailianConfig.builder()
                .accessKeyId(resolve(cfg.getAccessKeyId(), global.getAccessKeyId()))
                .accessKeySecret(resolve(cfg.getAccessKeySecret(), global.getAccessKeySecret()))
                .workspaceId(cfg.getWorkspaceId())
                .indexId(cfg.getIndexId());
        // 仅当 endpoint 非空才显式传入。否则保持 builder.endpoint 为 null，
        // 让 BailianConfig 构造时的 DEFAULT_ENDPOINT（bailian.cn-beijing.aliyuncs.com）生效。
        // 直接传 "" 会绕过默认逻辑，aliyun SDK 会以 "'config.endpoint' can not be empty" 抛错。
        if (endpoint != null && !endpoint.isBlank()) {
            b.endpoint(endpoint);
        }

        Boolean rerank = firstNonNull(cfg.getEnableRerank(), global.getDefaultEnableRerank(), Boolean.FALSE);
        Boolean rewrite = firstNonNull(cfg.getEnableRewrite(), global.getDefaultEnableRewrite(), Boolean.FALSE);

        if (Boolean.TRUE.equals(rerank)) {
            RerankConfig.Builder rb = RerankConfig.builder();
            if (cfg.getRerankModel() != null && !cfg.getRerankModel().isBlank()) {
                rb.modelName(cfg.getRerankModel());
            }
            if (cfg.getRerankMinScore() != null) {
                rb.rerankMinScore(cfg.getRerankMinScore());
            }
            if (cfg.getRerankTopN() != null) {
                rb.rerankTopN(cfg.getRerankTopN());
            }
            b.enableReranking(true);
            b.rerankConfig(rb.build());
        }
        if (Boolean.TRUE.equals(rewrite)) {
            RewriteConfig.Builder rb = RewriteConfig.builder();
            if (cfg.getRewriteModel() != null && !cfg.getRewriteModel().isBlank()) {
                rb.modelName(cfg.getRewriteModel());
            }
            b.enableRewrite(true);
            b.rewriteConfig(rb.build());
        }
        return b.build();
    }

    /** 合并 AgentSpec.bailian 与全局默认值：per-agent 字段优先。 */
    public BailianRagConfig mergeWithGlobal(BailianRagConfig specCfg) {
        if (specCfg == null) return null;
        BailianRagConfig merged = new BailianRagConfig();
        merged.setEnabled(specCfg.isEnabled());
        merged.setAccessKeyId(firstNonNull(specCfg.getAccessKeyId(), global.getAccessKeyId()));
        merged.setAccessKeySecret(firstNonNull(specCfg.getAccessKeySecret(), global.getAccessKeySecret()));
        merged.setEndpoint(firstNonNull(specCfg.getEndpoint(), global.getEndpoint()));
        merged.setWorkspaceId(specCfg.getWorkspaceId());
        merged.setIndexId(specCfg.getIndexId());
        merged.setLimit(firstNonNull(specCfg.getLimit(), global.getDefaultLimit(), 5));
        merged.setScoreThreshold(firstNonNull(specCfg.getScoreThreshold(), global.getDefaultScoreThreshold(), 0.0));
        merged.setEnableRerank(firstNonNull(specCfg.getEnableRerank(), global.getDefaultEnableRerank(), Boolean.FALSE));
        merged.setRerankModel(specCfg.getRerankModel());
        merged.setRerankMinScore(specCfg.getRerankMinScore());
        merged.setRerankTopN(specCfg.getRerankTopN());
        merged.setEnableRewrite(firstNonNull(specCfg.getEnableRewrite(), global.getDefaultEnableRewrite(), Boolean.FALSE));
        merged.setRewriteModel(specCfg.getRewriteModel());
        return merged;
    }

    /** 失效指定 agent 的工具缓存（spec 变更或删除时调用）。 */
    public void invalidate(String agentId) {
        toolCache.remove(agentId);
    }

    /** 全部失效（仅用于关闭/测试场景）。 */
    public void invalidateAll() {
        toolCache.clear();
    }

    // ===== private helpers =====

    /** 合并旧配置与新输入：secret 字段若传 "***" 或为空，则保留旧值。 */
    private static BailianRagConfig merge(BailianRagConfig old, BailianRagConfig input) {
        String secret = input.getAccessKeySecret();
        if ((secret == null || secret.isBlank() || BailianRagConfig.MASKED.equals(secret))
                && old != null) {
            secret = old.getAccessKeySecret();
        }
        BailianRagConfig out = new BailianRagConfig();
        out.setEnabled(true);
        out.setAccessKeyId(emptyOrKeep(input.getAccessKeyId(), old != null ? old.getAccessKeyId() : null));
        out.setAccessKeySecret(secret);
        out.setWorkspaceId(emptyOrKeep(input.getWorkspaceId(), old != null ? old.getWorkspaceId() : null));
        out.setIndexId(emptyOrKeep(input.getIndexId(), old != null ? old.getIndexId() : null));
        out.setEndpoint(emptyOrKeep(input.getEndpoint(), old != null ? old.getEndpoint() : null));
        out.setLimit(input.getLimit() != null ? input.getLimit() : (old != null ? old.getLimit() : null));
        out.setScoreThreshold(input.getScoreThreshold() != null ? input.getScoreThreshold()
                : (old != null ? old.getScoreThreshold() : null));
        out.setEnableRerank(input.getEnableRerank() != null ? input.getEnableRerank()
                : (old != null ? old.getEnableRerank() : null));
        out.setRerankModel(emptyOrKeep(input.getRerankModel(), old != null ? old.getRerankModel() : null));
        out.setRerankMinScore(input.getRerankMinScore() != null ? input.getRerankMinScore()
                : (old != null ? old.getRerankMinScore() : null));
        out.setRerankTopN(input.getRerankTopN() != null ? input.getRerankTopN()
                : (old != null ? old.getRerankTopN() : null));
        out.setEnableRewrite(input.getEnableRewrite() != null ? input.getEnableRewrite()
                : (old != null ? old.getEnableRewrite() : null));
        out.setRewriteModel(emptyOrKeep(input.getRewriteModel(), old != null ? old.getRewriteModel() : null));
        return out;
    }

    private static String emptyOrKeep(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String resolve(String perAgent, String globalValue) {
        if (perAgent != null && !perAgent.isBlank()) return perAgent;
        return globalValue;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) if (v != null) return v;
        return null;
    }
}