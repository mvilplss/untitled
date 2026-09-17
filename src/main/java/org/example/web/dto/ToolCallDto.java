package org.example.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "工具调用一次完整记录：模型决策 → 参数 → 执行结果")
public class ToolCallDto {

    @Schema(description = "工具调用唯一 ID（同次回复内稳定）", example = "call_xyz123")
    private String id;

    @Schema(description = "工具名称", example = "get_weather")
    private String name;

    @Schema(description = "模型生成的入参 JSON 字符串（流式中可增量）", example = "{\"city\":\"杭州\"}")
    private String arguments;

    @Schema(description = "工具执行输出（文本片段拼接后），可能为空")
    private String output;

    @Schema(description = "执行结果状态",
            example = "SUCCESS",
            allowableValues = {"SUCCESS", "ERROR", "INTERRUPTED", "DENIED", "RUNNING"})
    private String state;

    public ToolCallDto() {}

    public ToolCallDto(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public ToolCallDto(String id, String name, String arguments, String output, String state) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
        this.output = output;
        this.state = state;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArguments() { return arguments; }
    public void setArguments(String arguments) { this.arguments = arguments; }

    public String getOutput() { return output; }
    public void setOutput(String output) { this.output = output; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}