package org.example.agent;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.IsolationScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.example.bailian.BailianRagConfig;
import org.example.bot.DingTalkBotConfig;

import java.util.List;

@Schema(description = "Agent 配置定义。注册一个 Agent 需提供 ID、sysPrompt 与 modelName。可选 tools 字段用于按白名单筛选业务工具（filesystem/shell/web/memory/session/plan）。")
public class AgentSpec {

    @Schema(description = "Agent 唯一标识，全局注册表主键",
            example = "coder",
            requiredMode = Schema.RequiredMode.REQUIRED,
            pattern = "^[a-zA-Z0-9_-]{1,32}$",
            maxLength = 32)
    @NotBlank(message = "id 不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{1,32}$", message = "id 必须匹配 ^[a-zA-Z0-9_-]{1,32}$")
    private String id;

    @Schema(description = "Agent 显示名，必填",
            example = "Java专家",
            requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 64)
    @NotBlank(message = "name 不能为空")
    @Size(max = 64, message = "name 长度不能超过 64")
    private String name;

    @Schema(description = "Agent 系统提示词，决定 Agent 角色与回答风格",
            example = "你是一位资深Java工程师，擅长用简洁的语言解释技术问题。",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "sysPrompt 不能为空")
    private String sysPrompt;

    @Schema(description = "底层模型名（OpenAI 兼容接口所识别的模型标识）",
            example = "minimax-m3",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "modelName 不能为空")
    private String modelName;

    @Schema(description = "启用的工具名白名单。null 或空数组表示启用全部默认工具。框架内置的平台工具（subagent/team/skill_manage 等）始终保留。",
            example = "[\"read_file\", \"write_file\", \"execute\", \"web_search\"]")
    private List<String> tools;

    @Schema(description = "启用的 skill 名白名单。null 或空数组表示 agent 可使用全部全局 skill。skill 来源于 .agentscope/skills/。",
            example = "[\"java-coding\", \"sql-expert\"]")
    private List<String> skills;

    @Schema(description = "可选：钉钉机器人配置。设置后该 Agent 会启动一个专属 DingTalk Channel，1:1 绑定；变更或删除后立即生效，无需重启。")
    private DingTalkBotConfig dingtalk;

    @Schema(description = "可选：阿里云百炼知识库 RAG 配置。启用后会把 retrieve_knowledge 注入 Toolkit；变更或禁用后立即重建 HarnessAgent。accessKeyId/Secret 可省略，走 application.yml 全局默认。")
    private BailianRagConfig bailian;

    @Schema(description = "沙箱隔离 scope。USER=每用户独立容器；AGENT=同 Agent 所有用户共享；GLOBAL=全局共享。**创建后不可修改**。默认 USER。",
            example = "USER",
            allowableValues = {"USER", "AGENT", "GLOBAL"},
            defaultValue = "USER")
    private IsolationScope isolationScope = IsolationScope.USER;

    public AgentSpec() {}

    public AgentSpec(String id, String name, String sysPrompt, String modelName,
                     List<String> tools, List<String> skills) {
        this(id, name, sysPrompt, modelName, tools, skills, null, null);
    }

    public AgentSpec(String id, String name, String sysPrompt, String modelName,
                     List<String> tools, List<String> skills, DingTalkBotConfig dingtalk) {
        this(id, name, sysPrompt, modelName, tools, skills, dingtalk, null);
    }

    public AgentSpec(String id, String name, String sysPrompt, String modelName,
                     List<String> tools, List<String> skills, DingTalkBotConfig dingtalk,
                     BailianRagConfig bailian) {
        this.id = id;
        this.name = (name == null || name.isBlank()) ? id : name;
        this.sysPrompt = sysPrompt;
        this.modelName = modelName;
        this.tools = (tools == null || tools.isEmpty()) ? null : List.copyOf(tools);
        this.skills = (skills == null || skills.isEmpty()) ? null : List.copyOf(skills);
        this.dingtalk = dingtalk;
        this.bailian = bailian;
        this.isolationScope = IsolationScope.USER;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @JsonProperty("name")
    public String getName() { return (name == null || name.isBlank()) ? id : name; }
    public void setName(String name) { this.name = name; }

    public String getSysPrompt() { return sysPrompt; }
    public void setSysPrompt(String sysPrompt) { this.sysPrompt = sysPrompt; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public List<String> getTools() { return tools; }
    public void setTools(List<String> tools) {
        this.tools = (tools == null || tools.isEmpty()) ? null : List.copyOf(tools);
    }

    public List<String> getSkills() { return skills; }
    public void setSkills(List<String> skills) {
        this.skills = (skills == null || skills.isEmpty()) ? null : List.copyOf(skills);
    }

    public DingTalkBotConfig getDingtalk() {
        return dingtalk == null ? null : dingtalk.masked();
    }
    /** 内部使用：返回未掩码原值（含明文 secret）。不要序列化给前端。 */
    @JsonIgnore
    public DingTalkBotConfig getDingtalkRaw() { return dingtalk; }
    public void setDingtalk(DingTalkBotConfig dingtalk) { this.dingtalk = dingtalk; }

    public BailianRagConfig getBailian() {
        return bailian == null ? null : bailian.masked();
    }
    /** 内部使用：返回未掩码原值（含明文 secret）。不要序列化给前端。 */
    @JsonIgnore
    public BailianRagConfig getBailianRaw() { return bailian; }
    public void setBailian(BailianRagConfig bailian) { this.bailian = bailian; }

    /**
     * 获取 isolation scope。null 时 fallback USER（兼容旧 json / 旧 API 没传）。
     */
    public IsolationScope getIsolationScope() {
        return isolationScope == null ? IsolationScope.USER : isolationScope;
    }
    public void setIsolationScope(IsolationScope isolationScope) {
        this.isolationScope = isolationScope == null ? IsolationScope.USER : isolationScope;
    }
}