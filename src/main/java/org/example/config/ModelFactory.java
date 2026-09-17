package org.example.config;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class ModelFactory {

    private final AgentProperties.OpenAI cfg;
    private final ConcurrentHashMap<String, Model> cache = new ConcurrentHashMap<>();

    public ModelFactory(AgentProperties props) {
        this.cfg = props.getOpenai();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new IllegalStateException(
                    "agent.openai.api-key 未配置。请在 src/main/resources/application-local.yml 中填写真实的 API Key。");
        }
    }

    public Model getOrBuild(String modelName) {
        return cache.computeIfAbsent(modelName, this::build);
    }

    public AgentProperties.OpenAI config() {
        return cfg;
    }

    private Model build(String modelName) {
        return OpenAIChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .modelName(modelName)
                .apiKey(cfg.getApiKey())
                .build();
    }
}