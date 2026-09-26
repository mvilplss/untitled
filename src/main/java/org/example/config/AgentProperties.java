package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private OpenAI openai = new OpenAI();
    private Persistence persistence = new Persistence();
    private Config config = new Config();
    private Bailian bailian = new Bailian();
    private Sandbox sandbox = new Sandbox();

    public OpenAI getOpenai() { return openai; }
    public void setOpenai(OpenAI openai) { this.openai = openai; }

    public Persistence getPersistence() { return persistence; }
    public void setPersistence(Persistence persistence) { this.persistence = persistence; }

    public Config getConfig() { return config; }
    public void setConfig(Config config) { this.config = config; }

    public Bailian getBailian() { return bailian; }
    public void setBailian(Bailian bailian) { this.bailian = bailian; }

    public Sandbox getSandbox() { return sandbox; }
    public void setSandbox(Sandbox sandbox) { this.sandbox = sandbox; }

    public static class Persistence {
        private String path = ".agentscope/agents.json";

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    public static class Config {
        private String skillsDir = ".agentscope/skills";

        public String getSkillsDir() { return skillsDir; }
        public void setSkillsDir(String skillsDir) { this.skillsDir = skillsDir; }
    }

    /**
     * 全局百炼 RAG 默认配置。未在 AgentSpec.bailian 单独覆盖时使用。
     * 启用的 Agent 会在缺省字段上回退到此处的值。
     */
    public static class Bailian {
        private String accessKeyId = "";
        private String accessKeySecret = "";
        private String endpoint = "";
        private Integer defaultLimit = 5;
        private Double defaultScoreThreshold = 0.3;
        private Boolean defaultEnableRerank = false;
        private Boolean defaultEnableRewrite = false;

        public String getAccessKeyId() { return accessKeyId; }
        public void setAccessKeyId(String accessKeyId) { this.accessKeyId = accessKeyId; }

        public String getAccessKeySecret() { return accessKeySecret; }
        public void setAccessKeySecret(String accessKeySecret) { this.accessKeySecret = accessKeySecret; }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

        public Integer getDefaultLimit() { return defaultLimit; }
        public void setDefaultLimit(Integer defaultLimit) { this.defaultLimit = defaultLimit; }

        public Double getDefaultScoreThreshold() { return defaultScoreThreshold; }
        public void setDefaultScoreThreshold(Double defaultScoreThreshold) { this.defaultScoreThreshold = defaultScoreThreshold; }

        public Boolean getDefaultEnableRerank() { return defaultEnableRerank; }
        public void setDefaultEnableRerank(Boolean defaultEnableRerank) { this.defaultEnableRerank = defaultEnableRerank; }

        public Boolean getDefaultEnableRewrite() { return defaultEnableRewrite; }
        public void setDefaultEnableRewrite(Boolean defaultEnableRewrite) { this.defaultEnableRewrite = defaultEnableRewrite; }
    }

    /**
     * Docker sandbox（每个 Agent 一个容器）资源约束 + dws 凭证挂载。
     * 未配置时给一个保守默认，避免 tar 镜像/解压在 Docker Desktop macOS 默认上限下被 SIGKILL (exit=137)。
     */
    public static class Sandbox {
        /** 容器内存上限，传 null/空 表示不设（继承宿主机默认）。 */
        private String memory = "4g";
        /** CPU 核数上限，null 表示不设。 */
        private Double cpu = 2.0;
        /** docker run --network；none=完全隔离，bridge=可访问外网 + host.docker.internal。 */
        private String network = "bridge";
        /** 容器镜像（预装 Node 22 + 真实 dws + Python 3，见 docker/sandbox.Dockerfile）。 */
        private String image = "untitled-sandbox:latest";
        /** sandbox 文件系统快照根目录（LocalSnapshotSpec）。支持 ~ 前缀展开。 */
        private String snapshotRoot = "~/.agentscope/docker-snapshots";
        /** 沙箱空闲超时（分钟），超时后关闭容器。超时前有新调用则重置计时器。 */
        private long idleTimeoutMinutes = 5;
        /** 聊天附件上传根目录（host 侧）。沙箱 bind mount 到 /workspace/uploads/。支持 ~ 前缀展开。 */
        private String chatUploadsRoot = "~/.agentscope/chat-uploads";

        public String getMemory() { return memory; }
        public void setMemory(String memory) { this.memory = memory; }

        public Double getCpu() { return cpu; }
        public void setCpu(Double cpu) { this.cpu = cpu; }

        public String getNetwork() { return network; }
        public void setNetwork(String network) { this.network = network; }

        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }

        public String getSnapshotRoot() { return snapshotRoot; }
        public void setSnapshotRoot(String snapshotRoot) { this.snapshotRoot = snapshotRoot; }

        public long getIdleTimeoutMinutes() { return idleTimeoutMinutes; }
        public void setIdleTimeoutMinutes(long idleTimeoutMinutes) { this.idleTimeoutMinutes = idleTimeoutMinutes; }

        public String getChatUploadsRoot() { return chatUploadsRoot; }
        public void setChatUploadsRoot(String chatUploadsRoot) { this.chatUploadsRoot = chatUploadsRoot; }
    }

    public static class OpenAI {
        private String baseUrl = "https://api.openai.com";
        private String apiKey = "";
        private String workspaceRoot = ".agentscope/workspace";
        private int connectTimeoutSeconds = 60;
        private int readTimeoutSeconds = 120;
        private int triggerMessages = 30;
        private int keepMessages = 10;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getWorkspaceRoot() { return workspaceRoot; }
        public void setWorkspaceRoot(String workspaceRoot) { this.workspaceRoot = workspaceRoot; }

        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }

        public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
        public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }

        public int getTriggerMessages() { return triggerMessages; }
        public void setTriggerMessages(int triggerMessages) { this.triggerMessages = triggerMessages; }

        public int getKeepMessages() { return keepMessages; }
        public void setKeepMessages(int keepMessages) { this.keepMessages = keepMessages; }
    }
}