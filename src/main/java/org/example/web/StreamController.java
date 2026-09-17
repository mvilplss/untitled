package org.example.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.service.AgentService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
@Tag(name = "对话流式", description = "Agent SSE 流式调用接口。按 token 增量推送模型输出，适合前端实时渲染。")
public class StreamController {

    private final AgentService service;

    public StreamController(AgentService service) {
        this.service = service;
    }

    @Operation(
            summary = "SSE 流式对话",
            description = "以 text/event-stream 输出模型增量片段。事件类型："
                    + " 'thinking' 推理块；'message' 正文块；"
                    + "'tool_start'/'tool_delta'/'tool_end' 工具调用生命周期；"
                    + "'tool_result' 工具执行完成；"
                    + "'done' 结束哨兵。"
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "OK，返回 text/event-stream"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "参数缺失或非法",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            schema = @io.swagger.v3.oas.annotations.media.Schema(ref = "ErrorResponse"))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404", description = "Agent 不存在",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            schema = @io.swagger.v3.oas.annotations.media.Schema(ref = "ErrorResponse")))
    })
    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @Parameter(description = "Agent ID", example = "poet", required = true)
            @RequestParam String agentId,
            @Parameter(description = "会话 ID", example = "default")
            @RequestParam(required = false, defaultValue = "default") String sessionId,
            @Parameter(description = "用户标识", example = "alice")
            @RequestParam(required = false, defaultValue = "anonymous") String userId,
            @Parameter(description = "用户消息内容", example = "写一首关于秋天的诗", required = true)
            @RequestParam String message) {
        try {
            return service.stream(agentId, sessionId, userId, message);
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }
}