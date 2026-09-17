package org.example.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/agents")
@Tag(name = "Agent 管理", description = "Agent 的注册、查询与删除。Agent 一旦创建即驻留内存，重启失效（除非同名重建）。")
public class AgentAdminController {

    private final AgentRegistry registry;

    public AgentAdminController(AgentRegistry registry) {
        this.registry = registry;
    }

    @Operation(
            summary = "创建 Agent",
            description = "注册一个 Agent 到内存注册表，返回 201。重复 ID 返回 409。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "创建成功",
                    content = @Content(schema = @Schema(implementation = AgentSpec.class))),
            @ApiResponse(responseCode = "400", description = "参数校验失败",
                    content = @Content(schema = @Schema(ref = "ErrorResponse"))),
            @ApiResponse(responseCode = "409", description = "Agent ID 已存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @PostMapping
    public ResponseEntity<AgentSpec> create(@Valid @RequestBody AgentSpec spec) {
        AgentSpec created = registry.create(spec);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(
            summary = "列出所有 Agent",
            description = "按 ID 升序返回所有已注册 Agent。启动时为空。"
    )
    @ApiResponse(responseCode = "200", description = "OK",
            content = @Content(schema = @Schema(implementation = AgentSpec.class)))
    @GetMapping
    public List<AgentSpec> list() {
        return registry.list();
    }

    @Operation(
            summary = "按 ID 查询 Agent",
            description = "返回单个 Agent 配置详情。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = AgentSpec.class))),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @GetMapping("/{id}")
    public AgentSpec get(@Parameter(description = "Agent ID", example = "coder")
                         @PathVariable String id) {
        try {
            return registry.getSpec(id);
        } catch (NoSuchElementException e) {
            throw new org.example.web.NotFoundException(e.getMessage());
        }
    }

    @Operation(
            summary = "更新 Agent 配置",
            description = "替换指定 ID 的 Agent 配置（含 tools 白名单）。Agent ID 不可变更。会重建 HarnessAgent。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功",
                    content = @Content(schema = @Schema(implementation = AgentSpec.class))),
            @ApiResponse(responseCode = "400", description = "参数校验失败",
                    content = @Content(schema = @Schema(ref = "ErrorResponse"))),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @PutMapping("/{id}")
    public AgentSpec update(@Parameter(description = "Agent ID", example = "coder")
                            @PathVariable String id,
                            @Valid @RequestBody AgentSpec spec) {
        if (!id.equals(spec.getId())) {
            throw new IllegalArgumentException("path id '" + id + "' 与 body id '" + spec.getId() + "' 不一致");
        }
        try {
            return registry.update(id, spec);
        } catch (NoSuchElementException e) {
            throw new org.example.web.NotFoundException(e.getMessage());
        }
    }

    @Operation(
            summary = "按 ID 删除 Agent",
            description = "从注册表移除 Agent 并释放资源。不会删除其 workspace 文件。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@Parameter(description = "Agent ID", example = "coder")
                                       @PathVariable String id) {
        boolean removed = registry.delete(id);
        if (!removed) {
            throw new org.example.web.NotFoundException("agent '" + id + "' not found");
        }
        return ResponseEntity.noContent().build();
    }
}