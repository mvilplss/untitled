package org.example.dws.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.dws.DwsAuthEventBus;
import org.example.dws.DwsAuthService;
import org.example.dws.DwsAuditService;
import org.example.dws.DwsUserRegistry;
import org.example.dws.web.dto.DeviceFlowRequest;
import org.example.dws.web.dto.DeviceFlowResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "钉钉 DWS · 授权",
        description = "dws (dingtalk-workspace-cli) 的 device-flow OAuth 流程。每个本地 userId 独立授权、独立目录。")
@RestController
@RequestMapping("/api/dws/auth")
public class DwsAuthController {

    private final DwsAuthService authService;
    private final DwsAuthEventBus eventBus;
    private final DwsUserRegistry registry;
    private final DwsAuditService audit;

    public DwsAuthController(DwsAuthService authService,
                             DwsAuthEventBus eventBus,
                             DwsUserRegistry registry,
                             DwsAuditService audit) {
        this.authService = authService;
        this.eventBus = eventBus;
        this.registry = registry;
        this.audit = audit;
    }

    @Operation(
            summary = "发起 device-flow 授权",
            description = "后端生成 userId，调 dws `auth login --device`，返回 userCode + verificationUriComplete。"
                    + " 前端在浏览器打开 URL 完成授权；通过 SSE 订阅后续 phase。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = DeviceFlowResponse.class))),
            @ApiResponse(responseCode = "400", description = "参数非法或二进制不可用",
                    content = @Content(schema = @Schema(ref = "ErrorResponse"))),
            @ApiResponse(responseCode = "503", description = "dws 二进制缺失或下载失败",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @PostMapping("/device")
    public DeviceFlowResponse device(@Valid @RequestBody DeviceFlowRequest req) throws java.io.IOException {
        DwsAuthService.DeviceFlowInfo info = authService.startDeviceFlow(req.getDisplayName());
        DeviceFlowResponse resp = new DeviceFlowResponse();
        resp.userId = info.userId;
        resp.flowId = info.flowId;
        resp.userCode = info.userCode;
        resp.verificationUriComplete = info.verificationUriComplete;
        resp.intervalMs = info.intervalMs;
        resp.expiresIn = info.expiresIn;
        resp.expiresAt = info.expiresAt;
        audit.record(info.userId, "auth login --device", 0, 0, "device-flow started");
        return resp;
    }

    @Operation(
            summary = "SSE 订阅授权进度",
            description = "事件类型：flow_state（阶段变化）、done（结束）。前端用 EventSource 订阅。"
    )
    @GetMapping(value = "/stream/{userId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String userId) {
        return eventBus.register(userId);
    }

    @Operation(
            summary = "查询当前授权 phase（同步）",
            description = "轻量接口，与 SSE 二选一。前端可在 SSE 失败时降级使用。"
    )
    @GetMapping("/status")
    public Map<String, Object> status(
            @Parameter(description = "本地 userId", required = true)
            @RequestParam String userId) {
        DwsAuthEventBus.AuthPhase phase = authService.currentPhase();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("phase", phase.name().toLowerCase());
        body.put("registry", registry.find(userId).orElse(null));
        return body;
    }

    @Operation(summary = "清空该 userId 的所有授权",
            description = "调 dws auth reset + 物理删除用户目录 + 从 registry 移除。不可恢复。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "OK"),
            @ApiResponse(responseCode = "400", description = "userId 非法",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @PostMapping("/reset")
    public ResponseEntity<Void> reset(
            @Parameter(description = "本地 userId", required = true)
            @RequestParam String userId) throws java.io.IOException {
        authService.reset(userId);
        audit.recordError(userId, "auth reset", "ok", "user reset");
        return ResponseEntity.noContent().build();
    }
}
