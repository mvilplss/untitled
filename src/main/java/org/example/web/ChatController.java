package org.example.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.service.AgentService;
import org.example.web.dto.ChatRequest;
import org.example.web.dto.ChatResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
@Tag(name = "对话", description = "Agent 同步调用接口。阻塞等待模型完整回复后返回。")
public class ChatController {

    private final AgentService service;

    public ChatController(AgentService service) {
        this.service = service;
    }

    @Operation(
            summary = "同步对话",
            description = "向指定 Agent 发送用户消息，等待完整回复后返回。sessionId 相同则复用历史会话上下文。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = ChatResponse.class))),
            @ApiResponse(responseCode = "400", description = "参数校验失败（缺 agentId 或 message）",
                    content = @Content(schema = @Schema(ref = "ErrorResponse"))),
            @ApiResponse(responseCode = "404", description = "Agent 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest req) {
        try {
            AgentService.Reply r = service.reply(
                    req.getAgentId(),
                    nz(req.getSessionId(), "default"),
                    nz(req.getUserId(), "anonymous"),
                    req.getMessage());
            return new ChatResponse(r.reply(), r.thinking(), r.toolCalls());
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    private static String nz(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }
}