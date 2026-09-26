package org.example.bailian;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "阿里云百炼 RAG 知识库配置。绑定到一个 Agent 后，会把 BailianKnowledge 包成 retrieve_knowledge 工具注入到 HarnessAgent 的 Toolkit；Agent 可自主决定何时调用。变更保存后立即生效（会重建 HarnessAgent）。")
public class BailianRagConfig {

    @Schema(description = "是否启用 RAG。关闭时从 Toolkit 移除知识检索工具并清除配置", example = "true")
    private boolean enabled;

    @Schema(description = "阿里云访问密钥 AccessKey ID", example = "LTAIxxxxxxxxxxxx")
    private String accessKeyId;

    @Schema(description = "阿里云访问密钥 AccessKey Secret（列表/查询时掩码为 ***）",
            example = "xxxxxxxxxxxxxxxxxxxxxxxx")
    private String accessKeySecret;

    @Schema(description = "百炼应用 / 工作空间 ID（workspaceId）", example = "llm-xxxxxxxx")
    private String workspaceId;

    @Schema(description = "百炼知识库索引 ID（indexId）", example = "kb-xxxxxxxx")
    private String indexId;

    @Schema(description = "可选：自定义百炼 endpoint。一般留空走默认", example = "https://bailian.aliyuncs.com")
    private String endpoint;

    @Schema(description = "可选：检索返回 top K 文档数。范围 1-50，默认 5",
            example = "5", minimum = "1", maximum = "50")
    private Integer limit;

    @Schema(description = "可选：相似度分数阈值，低于此分的结果会被过滤。范围 0.0-1.0",
            example = "0.3", minimum = "0.0", maximum = "1.0")
    private Double scoreThreshold;

    @Schema(description = "可选：是否启用重排序（rerank）。启用会提高精度但增加延迟与配额消耗",
            example = "false")
    private Boolean enableRerank;

    @Schema(description = "可选：rerank 模型名。留空走百炼默认（如 gte-rerank-hybrid）",
            example = "gte-rerank-hybrid")
    private String rerankModel;

    @Schema(description = "可选：rerank 最小分，低于此分的结果会被过滤。范围 0.0-1.0",
            example = "0.3", minimum = "0.0", maximum = "1.0")
    private Float rerankMinScore;

    @Schema(description = "可选：rerank 后保留的 top N 文档数",
            example = "5", minimum = "1", maximum = "50")
    private Integer rerankTopN;

    @Schema(description = "可选：是否启用查询改写（多轮对话场景）。启用会利用会话历史优化召回",
            example = "false")
    private Boolean enableRewrite;

    @Schema(description = "可选：query rewrite 模型名。留空走百炼默认（如 conv-rewrite-qwen-1.8b）",
            example = "conv-rewrite-qwen-1.8b")
    private String rewriteModel;

    public BailianRagConfig() {}

    public BailianRagConfig(boolean enabled, String accessKeyId, String accessKeySecret,
                            String workspaceId, String indexId, String endpoint,
                            Integer limit, Double scoreThreshold,
                            Boolean enableRerank, String rerankModel,
                            Float rerankMinScore, Integer rerankTopN,
                            Boolean enableRewrite, String rewriteModel) {
        this.enabled = enabled;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
        this.workspaceId = workspaceId;
        this.indexId = indexId;
        this.endpoint = endpoint;
        this.limit = limit;
        this.scoreThreshold = scoreThreshold;
        this.enableRerank = enableRerank;
        this.rerankModel = rerankModel;
        this.rerankMinScore = rerankMinScore;
        this.rerankTopN = rerankTopN;
        this.enableRewrite = enableRewrite;
        this.rewriteModel = rewriteModel;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAccessKeyId() { return accessKeyId; }
    public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

    public String getAccessKeySecret() { return accessKeySecret; }
    public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

    public String getIndexId() { return indexId; }
    public void setIndexId(String indexId) { this.indexId = indexId; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public Integer getLimit() { return limit; }
    public void setLimit(Integer limit) { this.limit = limit; }

    public Double getScoreThreshold() { return scoreThreshold; }
    public void setScoreThreshold(Double scoreThreshold) { this.scoreThreshold = scoreThreshold; }

    public Boolean getEnableRerank() { return enableRerank; }
    public void setEnableRerank(Boolean enableRerank) { this.enableRerank = enableRerank; }

    public String getRerankModel() { return rerankModel; }
    public void setRerankModel(String rerankModel) { this.rerankModel = rerankModel; }

    public Float getRerankMinScore() { return rerankMinScore; }
    public void setRerankMinScore(Float rerankMinScore) { this.rerankMinScore = rerankMinScore; }

    public Integer getRerankTopN() { return rerankTopN; }
    public void setRerankTopN(Integer rerankTopN) { this.rerankTopN = rerankTopN; }

    public Boolean getEnableRewrite() { return enableRewrite; }
    public void setEnableRewrite(Boolean enableRewrite) { this.enableRewrite = enableRewrite; }

    public String getRewriteModel() { return rewriteModel; }
    public void setRewriteModel(String rewriteModel) { this.rewriteModel = rewriteModel; }

    @JsonIgnore
    public boolean isComplete() {
        return accessKeyId != null && !accessKeyId.isBlank()
                && accessKeySecret != null && !accessKeySecret.isBlank()
                && !MASKED.equals(accessKeySecret)
                && workspaceId != null && !workspaceId.isBlank()
                && indexId != null && !indexId.isBlank();
    }

    /**
     * 返回脱敏副本：enabled=true 且字段齐时，accessKeySecret 替换为 ***。
     * 其他字段原样返回。
     */
    public BailianRagConfig masked() {
        BailianRagConfig m = new BailianRagConfig();
        m.enabled = this.enabled;
        m.accessKeyId = this.accessKeyId;
        m.accessKeySecret = (this.accessKeySecret == null
                || this.accessKeySecret.isBlank()
                || MASKED.equals(this.accessKeySecret))
                ? this.accessKeySecret
                : MASKED;
        m.workspaceId = this.workspaceId;
        m.indexId = this.indexId;
        m.endpoint = this.endpoint;
        m.limit = this.limit;
        m.scoreThreshold = this.scoreThreshold;
        m.enableRerank = this.enableRerank;
        m.rerankModel = this.rerankModel;
        m.rerankMinScore = this.rerankMinScore;
        m.rerankTopN = this.rerankTopN;
        m.enableRewrite = this.enableRewrite;
        m.rewriteModel = this.rewriteModel;
        return m;
    }

    @JsonIgnore
    public static final String MASKED = "***";
}