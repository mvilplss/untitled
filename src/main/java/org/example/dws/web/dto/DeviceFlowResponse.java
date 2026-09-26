package org.example.dws.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "device-flow 响应体。前端拿到后展示 userCode / QR，并在 SSE 中订阅后续 phase。")
public class DeviceFlowResponse {

    @Schema(description = "本地 userId（后端生成 UUID）。后续请求通过 X-User-Id header 携带。",
            example = "u-3f9b2a1e8d0c4567")
    public String userId;

    @Schema(description = "dws 内部 flowId，调试用", example = "flow_abc123")
    public String flowId;

    @Schema(description = "用户设备码，供无摄像头场景手动输入",
            example = "ABCD-1234")
    public String userCode;

    @Schema(description = "填好 userCode 的完整 URL；用户在任何浏览器打开即可完成授权",
            example = "https://login.dingtalk.com/oauth2/device/verify.htm?user_code=ABCD-1234")
    public String verificationUriComplete;

    @Schema(description = "建议轮询间隔（毫秒）", example = "2000")
    public long intervalMs;

    @Schema(description = "device-code 有效秒数（dws 默认 900 = 15 分钟）", example = "900")
    public int expiresIn;

    @Schema(description = "device-code 过期时间戳（epoch ms）", example = "1763827200000")
    public long expiresAt;
}
