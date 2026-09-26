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
import org.example.dws.CurrentUserId;
import org.example.dws.DwsAuthService;
import org.example.dws.DwsAuditService;
import org.example.dws.DwsException;
import org.example.dws.DwsUserRegistry;
import org.example.dws.web.dto.DwsProfileDto;
import org.example.dws.web.dto.DwsSwitchRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "钉钉 DWS · Profile",
        description = "查询 / 切换 / 删除已登录的 DWS 账号 profile")
@RestController
@RequestMapping("/api/dws/profile")
public class DwsProfileController {

    private final DwsAuthService authService;
    private final DwsUserRegistry registry;
    private final DwsAuditService audit;

    public DwsProfileController(DwsAuthService authService,
                                DwsUserRegistry registry,
                                DwsAuditService audit) {
        this.authService = authService;
        this.registry = registry;
        this.audit = audit;
    }

    @Operation(summary = "列出当前 userId 下的所有 profile")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = DwsProfileDto.class))),
            @ApiResponse(responseCode = "401", description = "尚未授权",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @GetMapping("/list")
    public List<DwsProfileDto> list(
            @Parameter(description = "本地 userId（缺省走 X-User-Id header）")
            @RequestParam(required = false) String userId,
            @CurrentUserId(required = false) String headerUid) throws java.io.IOException {
        String uid = userId != null ? userId : headerUid;
        if (uid == null) throw new DwsException.UserNotFound("缺少 userId 参数或 X-User-Id 头");

        JsonNode root = authService.listProfiles(uid);

        // auth status 输出 {"success": true, "corp_id": "...", "user_id": "..."}
        List<DwsProfileDto> result = new ArrayList<>();
        if (root.path("success").asBoolean(false)) {
            DwsProfileDto dto = new DwsProfileDto();
            dto.userId = uid;
            dto.corpId = root.path("corp_id").asText("");
            dto.dwsUserId = root.path("user_id").asText("");
            dto.userName = root.path("user_name").asText("");
            dto.status = root.path("token_valid").asBoolean() ? "active" : "expired";
            dto.lastLoginAt = System.currentTimeMillis();
            result.add(dto);
        }
        return result;
    }

    @Operation(summary = "切换默认 profile")
    @PostMapping("/switch")
    public ResponseEntity<Void> switchProfile(
            @CurrentUserId String userId,
            @Valid @RequestBody DwsSwitchRequest body) throws java.io.IOException {
        authService.switchProfile(userId, body.selector);
        audit.record(userId, "profile switch " + body.selector, 0, 0, null);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "删除单个 profile")
    @DeleteMapping("/{corpId}:{dwsUserId}")
    public ResponseEntity<Void> removeProfile(
            @CurrentUserId String userId,
            @Parameter(description = "corpId", example = "ding1234567")
            @PathVariable String corpId,
            @Parameter(description = "dws userId", example = "user_abc")
            @PathVariable String dwsUserId) throws java.io.IOException {
        String selector = corpId + ":" + dwsUserId;
        authService.removeProfile(userId, selector);
        audit.record(userId, "auth logout --profile " + selector, 0, 0, null);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "查询本地注册表（userId → DWS 绑定）",
            description = "返回所有本地 userId 列表 + 当前 X-User-Id 对应的绑定记录")
    @GetMapping("/registry")
    public Map<String, Object> registry(@CurrentUserId String userId) {
        Map<String, DwsUserRegistry.RegistryEntry> all = registry.loadAll();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("current", all.get(userId));
        body.put("users", all);
        return body;
    }
}
