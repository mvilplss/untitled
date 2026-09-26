package org.example.dws.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "当前 userId 的已绑定 DWS profile")
public class DwsProfileDto {

    @Schema(description = "本地 userId", example = "u-3f9b2a1e")
    public String userId;

    @Schema(description = "显示名", example = "alice@corp")
    public String displayName;

    @Schema(description = "钉钉组织 corpId", example = "ding1234567")
    public String corpId;

    @Schema(description = "钉钉组织名", example = "示例公司")
    public String corpName;

    @Schema(description = "钉钉 userId", example = "user_abc")
    public String dwsUserId;

    @Schema(description = "钉钉用户名", example = "张三")
    public String userName;

    @Schema(description = "profile 状态", example = "active")
    public String status;

    @Schema(description = "最近一次授权时间戳（epoch ms）", example = "1763827200000")
    public Long lastLoginAt;
}
