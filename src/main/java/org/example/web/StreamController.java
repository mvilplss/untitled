package org.example.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.example.service.AgentService;
import org.example.web.dto.ChatRequest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
            description = "POST JSON body → SSE 响应（text/event-stream）。事件类型："
                    + " 'thinking' 推理块；'message' 正文块；"
                    + "'tool_start'/'tool_delta'/'tool_end' 工具调用生命周期；"
                    + "'tool_result' 工具执行完成；"
                    + "'tool_result_delta' 工具执行输出增量；"
                    + "'usage' 本次 LLM 调用的 token / 耗时统计；"
                    + "'done' 结束哨兵。"
                    + "attachments 为附件引用列表（先调 POST /api/agents/{id}/chat/upload 获取）。"
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
    @PostMapping(value = "/chat/stream",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@Valid @RequestBody ChatRequest req) {
        try {
            return service.stream(
                    req.getAgentId(),
                    nz(req.getSessionId(), "default"),
                    nz(req.getUserId(), "anonymous"),
                    req.getMessage(),
                    req.getAttachments());
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    private static String nz(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }
}
