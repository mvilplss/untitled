package org.example.bailian;

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
@RequestMapping("/api/agents/{id}/bailian")
@Tag(name = "数字人·百炼 RAG", description = "为数字人配置/查询/删除阿里云百炼知识库检索（RAG）。启用后会把 retrieve_knowledge 工具注入 HarnessAgent 的 Toolkit；变更后立即重建 HarnessAgent 生效。accessKeyId/secret 若留空走 application.yml 全局默认。")
public class BailianRagController {

    private final BailianRagService service;

    public BailianRagController(BailianRagService service) {
        this.service = service;
    }

    @Operation(
            summary = "查询数字人百炼 RAG 配置",
            description = "返回当前配置。accessKeySecret 字段一律返回 '***' 掩码。未配置时返回 enabled=false 空壳。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = BailianRagConfig.class))),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @GetMapping
    public BailianRagConfig get(
            @Parameter(description = "Agent ID", example = "coder")
            @PathVariable("id") String agentId) {
        try {
            return service.get(agentId);
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    @Operation(
            summary = "启/改数字人百炼 RAG",
            description = "应用新的 RAG 配置并重建 HarnessAgent，立即生效。accessKeySecret 若传 '***' 则保留旧值；任一字段留空时会回退到 application.yml 全局默认。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "应用成功（返回脱敏配置）",
                    content = @Content(schema = @Schema(implementation = BailianRagConfig.class))),
            @ApiResponse(responseCode = "400", description = "参数校验失败或配置不完整",
                    content = @Content(schema = @Schema(ref = "ErrorResponse"))),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @PutMapping
    public BailianRagConfig upsert(
            @Parameter(description = "Agent ID", example = "coder")
            @PathVariable("id") String agentId,
            @RequestBody BailianRagConfig body) {
        try {
            return service.upsert(agentId, body);
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    @Operation(
            summary = "停用并清除数字人百炼 RAG",
            description = "重建 HarnessAgent 移除 retrieve_knowledge 工具，并从 spec 中移除 bailian 字段。"
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
                return ResponseEntity.noContent().build();
            }
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }
}