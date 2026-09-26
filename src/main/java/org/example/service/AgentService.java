package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.harness.agent.HarnessAgent;
import org.example.agent.AgentRegistry;
import org.example.sandbox.SandboxFileService;
import org.example.web.dto.AttachmentDto;
import org.example.web.dto.ToolCallDto;
import org.example.web.dto.UsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
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
    private final SessionService sessionService;
    private final SandboxFileService sandboxFileService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentService(AgentRegistry registry, SessionService sessionService, SandboxFileService sandboxFileService) {
        this.registry = registry;
        this.sessionService = sessionService;
        this.sandboxFileService = sandboxFileService;
    }

/** 同步对话结果：reply / thinking / toolCalls / usages 分离 */
    public record Reply(String reply, String thinking, List<ToolCallDto> toolCalls, List<UsageDto> usages) {}

    public Reply reply(String agentId, String sessionId, String userId, String message, List<AttachmentDto> attachments) {
        return reply(agentId, sessionId, userId, message, attachments, null);
    }

    /**
     * 带超时阈值的同步对话（用于定时任务等长场景）。timeout 为 null 时行为等同无超时。
     * 超时由 {@code Flux.blockLast(Duration)} 触发：到达阈值后抛出 {@code IllegalStateException}，
     * 抛出时已累积的 textSink 已随外层 StringBuilder 丢失（blockLast 直接中断下游），
     * 调用方需自行处理超时状态。
     */
    public Reply reply(String agentId, String sessionId, String userId, String message,
                       List<AttachmentDto> attachments, Duration timeout) {
        HarnessAgent agent = registry.get(agentId);
        RuntimeContext ctx = ctx(sessionId, userId);
        log.debug("Sync call: agentId={}, sessionId={}, userId={}, message.len={}, timeout={}",
                agentId, sessionId, userId, message == null ? 0 : message.length(), timeout);
        StringBuilder sb = new StringBuilder();
        Map<String, ToolCallDto> toolCalls = new LinkedHashMap<>();
        UsageTracker tracker = new UsageTracker();
        UserMessage userMsg = ChatMessageBuilder.build(message, attachments);
        // 写附件 sidecar（同步路径也记录）
        if (attachments != null && !attachments.isEmpty()) {
            sessionService.appendAttachments(agentId, sessionId,
                    System.currentTimeMillis() / 1000, attachments);
        }
        if (timeout == null) {
            agent.streamEvents(userMsg, ctx)
                    .doOnNext(event -> handleSyncEvent(event, sb, toolCalls, tracker))
                    .blockLast();
        } else {
            agent.streamEvents(userMsg, ctx)
                    .doOnNext(event -> handleSyncEvent(event, sb, toolCalls, tracker))
                    .blockLast(timeout);
        }
        Reply split = splitFullText(sb.toString());
        return new Reply(split.reply(), split.thinking(), new ArrayList<>(toolCalls.values()), tracker.snapshot());
    }

    /**
     * 流式对话：以 ServerSentEvent 形式输出
     *   event: thinking        → 推理块 delta
     *   event: message         → 正文 delta
     *   event: tool_start      → 工具调用开始（id + name）
     *   event: tool_delta      → 工具入参增量（id + delta）
     *   event: tool_end        → 工具入参结束（id）
     *   event: tool_result     → 工具执行完成（id + name + output + state）
     *   event: tool_result_delta → 工具执行输出增量（id + name + delta）
     *   event: usage           → 本次 LLM 调用 token / 耗时统计（inputTokens, outputTokens, cachedTokens, totalTokens, time, seq, replyId, createsEntry）
     *   event: done            → 流结束哨兵
     */
    public Flux<ServerSentEvent<String>> stream(
            String agentId, String sessionId, String userId, String message,
            List<AttachmentDto> attachments) {
        HarnessAgent agent = registry.get(agentId);
        RuntimeContext ctx = ctx(sessionId, userId);
        log.debug("Stream call: agentId={}, sessionId={}, userId={}, message.len={}",
                agentId, sessionId, userId, message == null ? 0 : message.length());

        // toolCallId → 累积器（args 增量 / output 增量）；sessionId 锁定本次调用
        Map<String, ToolAccum> toolAcc = new LinkedHashMap<>();

        return Flux.defer(() -> {
            ThinkingParser parser = new ThinkingParser();
            UsageTracker tracker = new UsageTracker();
            UserMessage userMsg = ChatMessageBuilder.build(message, attachments);
            // 写附件 sidecar（流式路径也记录）
            if (attachments != null && !attachments.isEmpty()) {
                sessionService.appendAttachments(agentId, sessionId,
                        System.currentTimeMillis() / 1000, attachments);
                transferAttachmentsToSandbox(agentId, sessionId, userId, attachments);
            }
            Flux<ServerSentEvent<String>> all = agent.streamEvents(userMsg, ctx)
                    .concatMap(event -> toSse(event, parser, toolAcc, agentId, sessionId, tracker))
                    .limitRate(1, 1)
                    .publishOn(Schedulers.parallel(), 1);

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
            AgentEvent event, ThinkingParser parser, Map<String, ToolAccum> toolAcc,
            String agentId, String sessionId, UsageTracker tracker) {
        AgentEventType type = event.getType();
        try {
            if (type == AgentEventType.MODEL_CALL_START) {
                tracker.startCall(((ModelCallStartEvent) event).getReplyId());
                return Flux.empty();
            }
            if (type == AgentEventType.MODEL_CALL_END) {
                UsageDto dto = tracker.endCall(((ModelCallEndEvent) event), agentId, sessionId, sessionService);
                if (dto != null) {
                    try {
                        return Mono.just(sse("usage", mapper.writeValueAsString(dto))).flux();
                    } catch (Exception ex) {
                        log.warn("Failed to serialize usage: {}", ex.getMessage());
                    }
                }
                return Flux.empty();
            }
            if (type == AgentEventType.TEXT_BLOCK_DELTA) {
                String chunk = ((TextBlockDeltaEvent) event).getDelta();
                if (chunk != null && !chunk.isBlank()) tracker.markText();
                List<ThinkingParser.Chunk> parts = parser.feed(chunk);
                return Flux.fromIterable(parts).map(AgentService::sseChunk);
            }
            if (type == AgentEventType.THINKING_BLOCK_DELTA) {
                ThinkingBlockDeltaEvent e = (ThinkingBlockDeltaEvent) event;
                if (e.getDelta() != null && !e.getDelta().isBlank()) tracker.markText();
                if (e.getDelta() == null || e.getDelta().isEmpty()) return Flux.empty();
                return Mono.just(sse("thinking", e.getDelta())).flux();
            }
            switch (type) {
                case TOOL_CALL_START -> {
                    ToolCallStartEvent e = (ToolCallStartEvent) event;
                    toolAcc.put(e.getToolCallId(), new ToolAccum(e.getToolCallName()));
                    tracker.markTool();
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
                    if (!piece.isEmpty()) acc.output.append(piece);
                    if (e.getData() == null) return Flux.empty();
                    ObjectNode payload = mapper.createObjectNode();
                    payload.put("id", e.getToolCallId());
                    payload.put("name", e.getToolCallName() == null ? "" : e.getToolCallName());
                    payload.put("delta", piece);
                    return Mono.just(sse("tool_result_delta", mapper.writeValueAsString(payload))).flux();
                }
                case TOOL_RESULT_TEXT_DELTA -> {
                    ToolResultTextDeltaEvent e = (ToolResultTextDeltaEvent) event;
                    ToolAccum acc = toolAcc.computeIfAbsent(e.getToolCallId(),
                            k -> new ToolAccum(e.getToolCallName()));
                    if (e.getDelta() != null) acc.output.append(e.getDelta());
                    if (e.getDelta() == null || e.getDelta().isEmpty()) return Flux.empty();
                    ObjectNode payload = mapper.createObjectNode();
                    payload.put("id", e.getToolCallId());
                    payload.put("name", e.getToolCallName() == null ? "" : e.getToolCallName());
                    payload.put("delta", e.getDelta());
                    return Mono.just(sse("tool_result_delta", mapper.writeValueAsString(payload))).flux();
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

    /** 同步路径下统一处理一个事件：累积 text / toolCalls / usages */
    private void handleSyncEvent(AgentEvent event, StringBuilder textSink, Map<String, ToolCallDto> toolSink,
                                 UsageTracker tracker) {
        AgentEventType type = event.getType();
        if (type == AgentEventType.MODEL_CALL_START) {
            tracker.startCall(((ModelCallStartEvent) event).getReplyId());
            return;
        }
        if (type == AgentEventType.MODEL_CALL_END) {
            tracker.endCall(((ModelCallEndEvent) event), null, null, sessionService);
            return;
        }
        if (type == AgentEventType.TEXT_BLOCK_DELTA) {
            String delta = ((TextBlockDeltaEvent) event).getDelta();
            textSink.append(delta);
            if (delta != null && !delta.isBlank()) tracker.markText();
            return;
        }
        if (type == AgentEventType.THINKING_BLOCK_DELTA) {
            ThinkingBlockDeltaEvent e = (ThinkingBlockDeltaEvent) event;
            if (e.getDelta() != null) {
                textSink.append(e.getDelta());
                if (!e.getDelta().isBlank()) tracker.markText();
            }
            return;
        }
        switch (type) {
            case TOOL_CALL_START -> {
                ToolCallStartEvent e = (ToolCallStartEvent) event;
                toolSink.put(e.getToolCallId(), new ToolCallDto(e.getToolCallId(), e.getToolCallName()));
                tracker.markTool();
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

    /**
     * 把附件 docker cp 到沙箱容器内（通过 {@link SandboxFileService#uploadAttachments}）。
     * <p>在构造 UserMessage 前调用，确保 Agent 能在沙箱内读到附件。
     * <p>失败不阻塞 chat，仅 log warn。
     */
    private void transferAttachmentsToSandbox(String agentId, String sessionId, String userId,
                                              List<AttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) return;
        List<Map.Entry<String, byte[]>> entries = new ArrayList<>();
        for (AttachmentDto a : attachments) {
            if (a == null || a.getPath() == null) continue;
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(a.getPath()));
                String containerPath = a.getContainerPath() != null
                        ? a.getContainerPath()
                        : "/workspace/uploads/" + userId + "/" + a.getName();
                entries.add(Map.entry(containerPath, bytes));
            } catch (IOException e) {
                log.warn("Failed to read staged attachment {}: {}", a.getName(), e.getMessage());
            }
        }
        if (entries.isEmpty()) return;
        try {
            sandboxFileService.uploadAttachments(agentId, userId, sessionId, entries);
            log.debug("Transferred {} attachments to sandbox for {}/{}/{}", entries.size(), agentId, userId, sessionId);
        } catch (Exception e) {
            log.warn("Failed to transfer attachments to sandbox: {}", e.getMessage());
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
            return new Reply("", "", List.of(), List.of());
        }
        Matcher m = THINK_PATTERN.matcher(text);
        StringBuilder thinking = new StringBuilder();
        while (m.find()) {
            thinking.append(m.group(1));
        }
        String reply = m.replaceAll("").trim();
        return new Reply(reply, thinking.toString().trim(), List.of(), List.of());
    }

    /** 流式累积器：toolCallId → 增量拼接 */
    private static class ToolAccum {
        final String name;
        final StringBuilder arguments = new StringBuilder();
        final StringBuilder output = new StringBuilder();
        ToolAccum(String name) { this.name = name; }
    }

    /**
     * 单次 LLM 调用的 usage 追踪器。
     * <p>在 MODEL_CALL_START 时初始化；收集该次调用期间是否产生文本 / 工具调用；
     * 在 MODEL_CALL_END 时封装 UsageDto 并写入 sidecar，刷新状态等待下一次调用。
     */
    private static class UsageTracker {
        private final List<UsageDto> records = new ArrayList<>();
        private int seq = 0;
        private boolean active;
        private boolean sawText;
        private boolean sawTool;
        private String replyId;

        void startCall(String replyId) {
            this.active = true;
            this.sawText = false;
            this.sawTool = false;
            this.replyId = replyId;
        }

        void markText() { if (active) sawText = true; }
        void markTool() { if (active) sawTool = true; }

        /**
         * 结束本次调用：构造 UsageDto 并返回。返回 null 表示无有效数据（无 usage / 已被吞）。
         * 同步路径（agentId/sessionId=null）不写 sidecar，只累积到 records。
         */
        UsageDto endCall(ModelCallEndEvent event, String agentId, String sessionId, SessionService sessionService) {
            active = false;
            ChatUsage u = event.getUsage();
            if (u == null) return null;
            // 跳过纯 0 / 0 数据（极少数 provider 上无 usage 信息）
            if (u.getInputTokens() == 0 && u.getOutputTokens() == 0) return null;

            int input = u.getInputTokens();
            int output = u.getOutputTokens();
            int cached = u.getCachedTokens();
            UsageDto dto = new UsageDto(
                    seq++,
                    u.getTime(),
                    input,
                    output,
                    cached,
                    input + output,
                    replyId != null ? replyId : event.getReplyId(),
                    sawText || !sawTool);
            records.add(dto);

            // 同步路径不落盘（agentId=null 时表示 reply() 调用）；流式路径落到 sidecar 供历史查询
            if (agentId != null && sessionId != null) {
                sessionService.appendUsage(agentId, sessionId, dto);
            }
            return dto;
        }

        List<UsageDto> snapshot() {
            return new ArrayList<>(records);
        }
    }
}