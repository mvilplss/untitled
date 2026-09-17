package org.example.bot;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.web.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/agents/{id}/dingtalk")
@Tag(name = "数字人·钉钉机器人", description = "为数字人配置/查询/删除钉钉机器人。变更后立即生效，无需重启服务。")
public class DingTalkBotController {

    private final DingTalkBotService service;

    public DingTalkBotController(DingTalkBotService service) {
        this.service = service;
    }

    @Operation(
            summary = "查询数字人钉钉机器人配置",
            description = "返回当前配置。appSecret 字段一律返回 '***' 掩码。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = DingTalkBotConfig.class))),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @GetMapping
    public DingTalkBotConfig get(
            @Parameter(description = "Agent ID", example = "coder")
            @PathVariable("id") String agentId) {
        try {
            return service.get(agentId);
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    @Operation(
            summary = "启/改数字人钉钉机器人",
            description = "应用新的机器人配置并持久化，立即启动/重启 Channel。appSecret 若传 '***' 则保留旧值。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "应用成功（返回脱敏配置）",
                    content = @Content(schema = @Schema(implementation = DingTalkBotConfig.class))),
            @ApiResponse(responseCode = "400", description = "参数校验失败或配置不完整",
                    content = @Content(schema = @Schema(ref = "ErrorResponse"))),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @PutMapping
    public DingTalkBotConfig upsert(
            @Parameter(description = "Agent ID", example = "coder")
            @PathVariable("id") String agentId,
            @RequestBody DingTalkBotConfig body) {
        try {
            return service.upsert(agentId, body);
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    @Operation(
            summary = "停用并清除数字人钉钉机器人",
            description = "停止 Channel 并从 spec 中移除 dingtalk 字段。立即生效。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @DeleteMapping
    public ResponseEntity<Void> delete(
            @Parameter(description = "Agent ID", example = "coder")
            @PathVariable("id") String agentId) {
        try {
            boolean removed = service.disable(agentId);
            if (!removed) {
                // 字段本来就为空；按 REST 惯例直接 204
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }
}