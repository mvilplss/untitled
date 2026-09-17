package org.example.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.service.SessionService;
import org.example.web.dto.ChatHistoryMessage;
import org.example.web.dto.SessionInfo;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/agents/{id}")
@Tag(name = "会话历史", description = "读取 Agent 已落盘 session jsonl，返回会话列表与历史消息。")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @Operation(summary = "列出 Agent 所有 session",
            description = "扫描 workspace 下的 jsonl 文件，返回每个 session 的 id / 消息数 / 最近活跃时间。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = SessionInfo.class))),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @GetMapping("/sessions")
    public List<SessionInfo> listSessions(@PathVariable String id) {
        try {
            return sessionService.listSessions(id);
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    @Operation(summary = "读取 session 消息历史",
            description = "从磁盘 jsonl 读出该 session 所有 USER / ASSISTANT 消息，按时间正序。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = ChatHistoryMessage.class))),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatHistoryMessage> getHistory(
            @PathVariable String id,
            @Parameter(description = "会话 ID", example = "demo-session", required = true)
            @PathVariable String sessionId) {
        try {
            return sessionService.getHistory(id, sessionId);
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    @Operation(summary = "删除 session",
            description = "从磁盘删除该 session 的 jsonl 与 log.jsonl 文件。前端需同时清理 localStorage 缓存。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "400", description = "sessionId 非法（包含路径分隔符等）",
                    content = @Content(schema = @Schema(ref = "ErrorResponse"))),
            @ApiResponse(responseCode = "404", description = "Agent 或 session 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(
            @PathVariable String id,
            @Parameter(description = "会话 ID", required = true)
            @PathVariable String sessionId) {
        try {
            boolean removed = sessionService.deleteSession(id, sessionId);
            if (!removed) {
                throw new NotFoundException("session '" + sessionId + "' not found");
            }
            return ResponseEntity.noContent().build();
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }
}