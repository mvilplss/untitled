package org.example.dws.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

@Schema(description = "发起 dws device-flow 的请求体")
public class DeviceFlowRequest {

    @Schema(description = "用户自定账号显示名；后端生成 userId 时使用此名做前缀",
            example = "alice@corp", maxLength = 64)
    @NotBlank(message = "displayName 不能为空")
    @Pattern(regexp = "^[\\u4e00-\\u9fa5\\w\\-@.]{1,64}$",
            message = "displayName 仅支持中英文/数字/_-@.，长度 ≤ 64")
    private String displayName;

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
