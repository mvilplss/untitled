package org.example.dws.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "dws 调用错误结构")
public class DwsError {

    @Schema(description = "机器可读错误码",
            example = "invoke_failed",
            allowableValues = {"binary_missing", "user_not_found", "unauthorized",
                    "invoke_failed", "invoke_timeout", "spawn_failed",
                    "device_flow_parse_failed", "missing_user_id", "path_traversal"})
    public String code;

    @Schema(description = "面向用户的提示信息", example = "dws exit 2: token expired")
    public String message;

    @Schema(description = "dws 退出码（仅 invoke_failed 时有值）", example = "2")
    public Integer exitCode;

    @Schema(description = "建议的后续操作（如重新授权）")
    public java.util.List<String> actions;
}
