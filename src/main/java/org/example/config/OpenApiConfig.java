package org.example.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Untitled Agent API")
                        .version("1.0.0")
                        .description("动态多 Agent HTTP 接口文档。支持运行时创建/删除 Agent，每个 Agent 拥有独立的 sysPrompt、modelName 与 session/memory 上下文。"))
                .components(new Components()
                        .addSchemas("ErrorResponse", new Schema<>()
                                .type("object")
                                .description("统一错误响应体")
                                .addProperty("status", new IntegerSchema()
                                        .description("HTTP 状态码")
                                        .example(400))
                                .addProperty("error", new StringSchema()
                                        .description("错误描述信息")
                                        .example("agentId 不能为空，请先调用 POST /api/agents 创建"))));
    }
}