package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.example.agent.AgentRegistry;
import org.example.web.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    /** 同步接口一次性剥离推理块的正则 */
    private static final Pattern THINK_PATTERN = Pattern.compile(
            "<(?:think|reasoning)>([\\s\\S]*?)</(?:think|reasoning)>");

    private final AgentRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentService(AgentRegistry registry) {
        this.registry = registry;
    }

    /** 同步对话结果：reply / thinking / toolCalls 分离 */
    public record Reply(String reply, String thinking, List<ToolCallDto> toolCalls) {}

    public Reply reply(String agentId, String sessionId, String userId, String message) {
        HarnessAgent agent = registry.get(agentId);
        RuntimeContext ctx = ctx(sessionId, userId);
        log.debug("Sync call: agentId={}, sessionId={}, userId={}, message.len={}",
                agentId, sessionId, userId, message == null ? 0 : message.length());
        StringBuilder sb = new StringBuilder();
        Map<String, ToolCallDto> toolCalls = new LinkedHashMap<>();
        agent.streamEvents(new UserMessage(message), ctx)
                .doOnNext(event -> handleSyncEvent(event, sb, toolCalls))
                .blockLast();
        Reply split = splitFullText(sb.toString());
        return new Reply(split.reply(), split.thinking(), new ArrayList<>(toolCalls.values()));
    }

    /**
     * 流式对话：以 ServerSentEvent 形式输出
     *   event: thinking        → 推理块 delta
     *   event: message         → 正文 delta
     *   event: tool_start      → 工具调用开始（id + name）
     *   event: tool_delta      → 工具入参增量（id + delta）
     *   event: tool_end        → 工具入参结束（id）
     *   event: tool_result     → 工具执行完成（id + name + output + state）
     *   event: done            → 流结束哨兵
     */
    public Flux<ServerSentEvent<String>> stream(
            String agentId, String sessionId, String userId, String message) {
        HarnessAgent agent = registry.get(agentId);
        RuntimeContext ctx = ctx(sessionId, userId);
        log.debug("Stream call: agentId={}, sessionId={}, userId={}, message.len={}",
                agentId, sessionId, userId, message == null ? 0 : message.length());

        // toolCallId → 累积器（args 增量 / output 增量）
        Map<String, ToolAccum> toolAcc = new LinkedHashMap<>();

        return Flux.defer(() -> {
            ThinkingParser parser = new ThinkingParser();
            Flux<ServerSentEvent<String>> all = agent.streamEvents(new UserMessage(message), ctx)
                    .flatMap(event -> toSse(event, parser, toolAcc));

            Flux<ServerSentEvent<String>> tail = Flux.defer(() -> {
                ThinkingParser.Chunk last = parser.flush();
                if (last == null) return Flux.empty();
                return Flux.just(sseChunk(last));
            });

            return all.concatWith(tail).concatWith(Flux.just(
                    ServerSentEvent.<String>builder().event("done").data("").build()));
        });
    }

    /** 把单个事件翻译成一个或零个 SSE；文本 delta 走 ThinkingParser 切分；tool 事件直接转 SSE */
    private Flux<ServerSentEvent<String>> toSse(
            AgentEvent event, ThinkingParser parser, Map<String, ToolAccum> toolAcc) {
        AgentEventType type = event.getType();
        try {
            if (type == AgentEventType.TEXT_BLOCK_DELTA) {
                String chunk = ((TextBlockDeltaEvent) event).getDelta();
                List<ThinkingParser.Chunk> parts = parser.feed(chunk);
                if (parts.isEmpty()) return Flux.empty();
                Flux<ServerSentEvent<String>> f = Flux.empty();
                for (ThinkingParser.Chunk p : parts) f = f.concatWith(Flux.just(sseChunk(p)));
                return f;
            }
            switch (type) {
                case TOOL_CALL_START -> {
                    ToolCallStartEvent e = (ToolCallStartEvent) event;
                    toolAcc.put(e.getToolCallId(), new ToolAccum(e.getToolCallName()));
                    return Mono.just(sse("tool_start", mapper.writeValueAsString(
                            Map.of("id", e.getToolCallId(), "name", e.getToolCallName())))).flux();
                }
                case TOOL_CALL_DELTA -> {
                    ToolCallDeltaEvent e = (ToolCallDeltaEvent) event;
                    ToolAccum acc = toolAcc.computeIfAbsent(e.getToolCallId(),
                            k -> new ToolAccum(e.getToolCallName()));
                    acc.arguments.append(e.getDelta());
                    return Mono.just(sse("tool_delta", mapper.writeValueAsString(
                            Map.of("id", e.getToolCallId(), "delta", e.getDelta())))).flux();
                }
                case TOOL_CALL_END -> {
                    ToolCallEndEvent e = (ToolCallEndEvent) event;
                    return Mono.just(sse("tool_end", mapper.writeValueAsString(
                            Map.of("id", e.getToolCallId())))).flux();
                }
                case TOOL_RESULT_START -> {
                    ToolResultStartEvent e = (ToolResultStartEvent) event;
                    toolAcc.computeIfAbsent(e.getToolCallId(), k -> new ToolAccum(e.getToolCallName()));
                    return Flux.empty();
                }
                case TOOL_RESULT_DATA_DELTA -> {
                    ToolResultDataDeltaEvent e = (ToolResultDataDeltaEvent) event;
                    ToolAccum acc = toolAcc.computeIfAbsent(e.getToolCallId(),
                            k -> new ToolAccum(e.getToolCallName()));
                    String piece = renderContentBlock(e.getData());
                    acc.output.append(piece);
                    return Flux.empty();
                }
                case TOOL_RESULT_TEXT_DELTA -> {
                    ToolResultTextDeltaEvent e = (ToolResultTextDeltaEvent) event;
                    ToolAccum acc = toolAcc.computeIfAbsent(e.getToolCallId(),
                            k -> new ToolAccum(e.getToolCallName()));
                    if (e.getDelta() != null) acc.output.append(e.getDelta());
                    return Flux.empty();
                }
                case TOOL_RESULT_END -> {
                    ToolResultEndEvent e = (ToolResultEndEvent) event;
                    ToolAccum acc = toolAcc.computeIfAbsent(e.getToolCallId(),
                            k -> new ToolAccum(e.getToolCallName()));
                    String stateName = e.getState() == null ? "SUCCESS" : e.getState().name();
                    ObjectNode payload = mapper.createObjectNode();
                    payload.put("id", e.getToolCallId());
                    payload.put("name", e.getToolCallName());
                    payload.put("state", stateName);
                    payload.put("output", acc.output.toString());
                    return Mono.just(sse("tool_result", mapper.writeValueAsString(payload))).flux();
                }
                default -> {
                    return Flux.empty();
                }
            }
        } catch (Exception ex) {
            log.warn("Failed to translate event {}: {}", type, ex.getMessage());
            return Flux.empty();
        }
    }

    private static ServerSentEvent<String> sseChunk(ThinkingParser.Chunk c) {
        return ServerSentEvent.<String>builder()
                .event(c.kind() == ThinkingParser.Kind.THINKING ? "thinking" : "message")
                .data(c.content())
                .build();
    }

    private static ServerSentEvent<String> sse(String event, String data) {
        return ServerSentEvent.<String>builder().event(event).data(data).build();
    }

    /** 把 ToolResult 增量数据块渲染成纯文本（TextBlock 取 text；其他走 toString） */
    private static String renderContentBlock(io.agentscope.core.message.ContentBlock block) {
        if (block instanceof TextBlock tb) {
            return tb.getText();
        }
        return block == null ? "" : block.toString();
    }

    /** 同步路径下统一处理一个事件：累积 text / toolCalls */
    private void handleSyncEvent(AgentEvent event, StringBuilder textSink, Map<String, ToolCallDto> toolSink) {
        AgentEventType type = event.getType();
        if (type == AgentEventType.TEXT_BLOCK_DELTA) {
            textSink.append(((TextBlockDeltaEvent) event).getDelta());
            return;
        }
        switch (type) {
            case TOOL_CALL_START -> {
                ToolCallStartEvent e = (ToolCallStartEvent) event;
                toolSink.put(e.getToolCallId(), new ToolCallDto(e.getToolCallId(), e.getToolCallName()));
            }
            case TOOL_CALL_DELTA -> {
                ToolCallDeltaEvent e = (ToolCallDeltaEvent) event;
                ToolCallDto dto = toolSink.computeIfAbsent(e.getToolCallId(),
                        k -> new ToolCallDto(e.getToolCallId(), e.getToolCallName()));
                dto.setArguments((dto.getArguments() == null ? "" : dto.getArguments()) + e.getDelta());
            }
            case TOOL_RESULT_DATA_DELTA -> {
                ToolResultDataDeltaEvent e = (ToolResultDataDeltaEvent) event;
                ToolCallDto dto = toolSink.computeIfAbsent(e.getToolCallId(),
                        k -> new ToolCallDto(e.getToolCallId(), e.getToolCallName()));
                if (dto.getName() == null) dto.setName(e.getToolCallName());
                String piece = renderContentBlock(e.getData());
                dto.setOutput((dto.getOutput() == null ? "" : dto.getOutput()) + piece);
            }
            case TOOL_RESULT_TEXT_DELTA -> {
                ToolResultTextDeltaEvent e = (ToolResultTextDeltaEvent) event;
                ToolCallDto dto = toolSink.computeIfAbsent(e.getToolCallId(),
                        k -> new ToolCallDto(e.getToolCallId(), e.getToolCallName()));
                if (dto.getName() == null) dto.setName(e.getToolCallName());
                if (e.getDelta() != null) {
                    dto.setOutput((dto.getOutput() == null ? "" : dto.getOutput()) + e.getDelta());
                }
            }
            case TOOL_RESULT_END -> {
                ToolResultEndEvent e = (ToolResultEndEvent) event;
                ToolCallDto dto = toolSink.computeIfAbsent(e.getToolCallId(),
                        k -> new ToolCallDto(e.getToolCallId(), e.getToolCallName()));
                if (dto.getName() == null) dto.setName(e.getToolCallName());
                dto.setState(e.getState() == null ? "SUCCESS" : e.getState().name());
            }
            default -> { /* 其他事件忽略 */ }
        }
    }

    private static RuntimeContext ctx(String sessionId, String userId) {
        return RuntimeContext.builder()
                .sessionId(sessionId == null || sessionId.isBlank() ? "default" : sessionId)
                .userId(userId == null || userId.isBlank() ? "anonymous" : userId)
                .build();
    }

    /** 一次性文本里剥离 <think>/<reasoning> 块 */
    private Reply splitFullText(String text) {
        if (text == null || text.isEmpty()) {
            return new Reply("", "", List.of());
        }
        Matcher m = THINK_PATTERN.matcher(text);
        StringBuilder thinking = new StringBuilder();
        while (m.find()) {
            thinking.append(m.group(1));
        }
        String reply = m.replaceAll("").trim();
        return new Reply(reply, thinking.toString().trim(), List.of());
    }

    /** 流式累积器：toolCallId → 增量拼接 */
    private static class ToolAccum {
        final String name;
        final StringBuilder arguments = new StringBuilder();
        final StringBuilder output = new StringBuilder();
        ToolAccum(String name) { this.name = name; }
    }
}