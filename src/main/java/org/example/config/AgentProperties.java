package org.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private OpenAI openai = new OpenAI();
    private Persistence persistence = new Persistence();
    private Config config = new Config();

    public OpenAI getOpenai() { return openai; }
    public void setOpenai(OpenAI openai) { this.openai = openai; }

    public Persistence getPersistence() { return persistence; }
    public void setPersistence(Persistence persistence) { this.persistence = persistence; }

    public Config getConfig() { return config; }
    public void setConfig(Config config) { this.config = config; }

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