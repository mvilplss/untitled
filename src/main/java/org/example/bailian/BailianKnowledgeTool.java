package org.example.bailian;

import com.aliyun.bailian20231229.models.RetrieveResponse;
import com.aliyun.bailian20231229.models.RetrieveResponseBody;
import io.agentscope.core.rag.integration.bailian.BailianClient;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * 自实现的 retrieve_knowledge 工具 —— 完全脱离 AgentScope v1 RAG API。
 *
 * <p>v1 RAG 接口（{@code Knowledge} / {@code KnowledgeRetrievalTools} / {@code RAGMode}）在 2.0.0
 * 全部标了 {@code @Deprecated(forRemoval = true, since = "2.0.0")}，官方正在重写。本类直接调
 * 阿里云百炼 SDK 的 {@link BailianClient#retrieve(String, String, Integer)}，自解析返回的
 * {@link RetrieveResponse} 并拼成 LLM 友好的纯文本。
 *
 * <p>调用入口：{@link Toolkit#registerTool(Object)} —— 该方法扫描对象方法上的 {@link Tool}
 * 注解并注册为 Agent 可调用的工具，不依赖任何 v1 RAG 类型。
 *
 * <p>当 AgentScope 发布 v2 RAG API 后，本类可整体替换；调用方（{@code BailianRagService}）
 * 不受影响。
 */
public class BailianKnowledgeTool {

    private static final Logger log = LoggerFactory.getLogger(BailianKnowledgeTool.class);

    private final BailianClient client;
    private final String indexId;
    private final int defaultTopK;
    private final double defaultScoreThreshold;
    /** 单次检索硬超时（防止 Bailian 服务挂起时永久占用 worker 线程） */
    private static final Duration RETRIEVE_TIMEOUT = Duration.ofSeconds(60);

    public BailianKnowledgeTool(BailianClient client,
                                String indexId,
                                int defaultTopK,
                                double defaultScoreThreshold) {
        this.client = client;
        this.indexId = indexId;
        this.defaultTopK = defaultTopK > 0 ? defaultTopK : 5;
        this.defaultScoreThreshold = defaultScoreThreshold;
    }

    private String workspaceId() {
        // BailianClient 在构造时已持有 workspaceId；这里仅供日志取用，避免重复存储
        return client.getWorkspaceId();
    }

    @Tool(
            name = "retrieve_knowledge",
            description = "按 query 检索阿里云百炼知识库，返回 top-K 条相关文档片段（带分数）。"
                    + "返回空字符串或'No results'表示无命中。调用时建议传入清晰、与知识库内容语义相关的 query。",
            readOnly = true,
            concurrencySafe = true
    )
    public String retrieveKnowledge(
            @ToolParam(name = "query", description = "检索关键词或问题，应当用知识库中可能出现的措辞")
            String query,
            @ToolParam(name = "top_k", required = false,
                    description = "返回 top K 条结果。范围 1-50，留空走默认")
            Integer topK,
            @ToolParam(name = "score_threshold", required = false,
                    description = "相似度分数阈值 0.0-1.0，低于此分的结果会被过滤。留空走默认")
            Double scoreThreshold
    ) {
        if (query == null || query.isBlank()) {
            return "Error: query 不能为空。";
        }
        int k = topK != null ? topK : defaultTopK;
        double threshold = scoreThreshold != null ? scoreThreshold : defaultScoreThreshold;

        RetrieveResponse response;
        try {
            // BailianClient.retrieve(String indexId, String query, Integer limit)
            // workspaceId 已经在 client 构造时绑定，不再作为参数传入
            response = client.retrieve(indexId, query, k)
                    .timeout(RETRIEVE_TIMEOUT)
                    .block();
        } catch (RuntimeException e) {
            // Mono.timeout 在超时时通过 reactor 通道 emit java.util.concurrent.TimeoutException
            // （被包装为 RuntimeException 抛出）；其他运行时异常一并在此处理
            String reason = e.getCause() instanceof TimeoutException
                    ? "timeout (> " + RETRIEVE_TIMEOUT.toSeconds() + "s)"
                    : e.getMessage();
            if (e.getCause() instanceof TimeoutException) {
                log.warn("Bailian retrieve timed out (workspace={}, index={}, limit={})",
                        workspaceId(), indexId, k);
                return "Error: 百炼检索超时（>" + RETRIEVE_TIMEOUT.toSeconds()
                        + "s）。请缩小 topK 或检查知识库状态。";
            }
            log.warn("Bailian retrieve failed (workspace={}, index={}): {}",
                    workspaceId(), indexId, e.getMessage());
            return "Error: 百炼检索失败：" + (reason == null ? e.getClass().getSimpleName() : reason);
        }

        if (response == null || response.getBody() == null || response.getBody().getData() == null) {
            return "No results.";
        }
        RetrieveResponseBody body = response.getBody();
        if (Boolean.FALSE.equals(body.getSuccess())) {
            return "Error: 百炼返回错误 code=" + body.getCode() + " message=" + body.getMessage();
        }
        List<RetrieveResponseBody.RetrieveResponseBodyDataNodes> nodes = body.getData().getNodes();
        if (nodes == null || nodes.isEmpty()) {
            return "No results.";
        }

        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int idx = 1;
        for (RetrieveResponseBody.RetrieveResponseBodyDataNodes node : nodes) {
            if (node == null) continue;
            Double score = node.getScore();
            double s = score != null ? score : 0.0;
            if (s < threshold) continue;
            String text = node.getText();
            if (text == null || text.isBlank()) continue;
            sb.append('[').append(idx++).append("] (score=")
                    .append(String.format("%.3f", s)).append(")\n")
                    .append(text.trim()).append("\n\n");
            shown++;
            if (shown >= k) break;
        }
        if (shown == 0) {
            return "No results above score threshold " + threshold + ".";
        }
        return sb.toString().trim();
    }
}