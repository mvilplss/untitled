package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.AgentRegistry;
import org.example.web.dto.ChatHistoryMessage;
import org.example.web.dto.SessionInfo;
import org.example.web.dto.ToolCallDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 AgentScope 落盘的 session jsonl 文件，向前端暴露 sessions 列表与历史消息。
 * 不复制或迁移原始数据，仅做只读视图。
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final Pattern THINK_PATTERN = Pattern.compile(
            "<(?:think|reasoning)>([\\s\\S]*?)</(?:think|reasoning)>");

    private final AgentRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    public SessionService(AgentRegistry registry) {
        this.registry = registry;
    }

    /** 列出某 agent 下所有 session 的元信息（跳过空 jsonl） */
    public List<SessionInfo> listSessions(String agentId) {
        Path sessionsDir = findSessionsDir(agentId);
        if (sessionsDir == null || !Files.isDirectory(sessionsDir)) {
            return Collections.emptyList();
        }
        List<SessionInfo> result = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sessionsDir, "*.jsonl")) {
            for (Path file : ds) {
                if (file.getFileName().toString().endsWith(".log.jsonl")) continue;
                SessionInfo info = readSessionMeta(file);
                // 过滤空 jsonl（网络异常或中途中断产生的 0 字节文件）
                if (info != null && info.getMessageCount() > 0) result.add(info);
            }
        } catch (IOException e) {
            log.warn("Failed to list sessions for {}: {}", agentId, e.getMessage());
        }
        result.sort(Comparator.comparingLong(SessionInfo::getLastActive).reversed());
        return result;
    }

    /**
     * 删除某 session 的 jsonl + log.jsonl 文件。
     * sessionId 必须仅含安全字符（防路径穿越）。
     * @return true 至少删除了一个文件；false 文件不存在
     */
    public boolean deleteSession(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()
                || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        Path sessionsDir = findSessionsDir(agentId);
        if (sessionsDir == null) return false;

        boolean removed = false;
        for (String suffix : new String[]{".jsonl", ".log.jsonl"}) {
            Path target = sessionsDir.resolve(sessionId + suffix);
            try {
                if (Files.deleteIfExists(target)) {
                    removed = true;
                    log.info("Deleted session file: {}", target);
                }
            } catch (IOException e) {
                log.warn("Failed to delete {}: {}", target, e.getMessage());
            }
        }
        return removed;
    }

    /**
     * 读取指定 session 的消息历史，按时间正序。
     * 同时解析 tool_use/tool_result 事件，把工具调用挂到对应 ASSISTANT 消息下。
     */
    public List<ChatHistoryMessage> getHistory(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.contains("..")
                || sessionId.contains("/") || sessionId.contains("\\")) {
            return Collections.emptyList();
        }
        Path sessionsDir = findSessionsDir(agentId);
        if (sessionsDir == null) return Collections.emptyList();
        Path file = sessionsDir.resolve(sessionId + ".jsonl");
        if (!Files.isRegularFile(file)) return Collections.emptyList();

        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            log.warn("Failed to read history {}/{}: {}", agentId, sessionId, e.getMessage());
            return Collections.emptyList();
        }

        // ===== Pass 1: 把 tool_use 按 parentId（=ASSISTANT message.id）分组；tool_result 按 toolCallId 索引 =====
        Map<String, List<JsonNode>> toolUsesByParent = new HashMap<>();
        Map<String, JsonNode> toolResultsByCallId = new HashMap<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            try {
                JsonNode n = mapper.readTree(line);
                String type = n.path("type").asText("");
                if ("tool_use".equals(type)) {
                    String parentId = n.path("parentId").asText("");
                    if (!parentId.isEmpty()) {
                        toolUsesByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(n);
                    }
                } else if ("tool_result".equals(type)) {
                    String tcId = n.path("toolCallId").asText("");
                    if (!tcId.isEmpty()) toolResultsByCallId.put(tcId, n);
                }
            } catch (IOException e) {
                // skip malformed line
            }
        }

        // ===== Pass 2: 按原始顺序遍历，仅构建 message，遇到 ASSISTANT 挂上 toolCalls =====
        List<ChatHistoryMessage> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank()) continue;
            JsonNode n;
            try { n = mapper.readTree(line); } catch (IOException e) { continue; }

            String type = n.path("type").asText("");
            if (!"message".equals(type)) continue;
            String role = n.path("role").asText("").toUpperCase();
            if (!"USER".equals(role) && !"ASSISTANT".equals(role)) continue;

            ChatHistoryMessage msg = parseMessage(n);
            if (msg == null) continue;

            if ("ASSISTANT".equals(role)) {
                String msgId = n.path("id").asText("");
                List<JsonNode> children = toolUsesByParent.get(msgId);
                if (children != null && !children.isEmpty()) {
                    msg.setToolCalls(buildToolCalls(children, toolResultsByCallId));
                }
            }
            out.add(msg);
        }
        return out;
    }

    /**
     * 从一组 tool_use JsonNode 构造 ToolCallDto 列表，按 jsonl 出现顺序；
     * 通过 toolCallId 在 toolResultsByCallId 里查找对应 output。
     */
    private List<ToolCallDto> buildToolCalls(List<JsonNode> toolUses,
                                             Map<String, JsonNode> toolResultsByCallId) {
        List<ToolCallDto> tcs = new ArrayList<>();
        for (JsonNode tu : toolUses) {
            String tcId = tu.path("toolCallId").asText("");
            String name = tu.path("name").asText("");
            String args;
            JsonNode input = tu.path("input");
            if (input.isMissingNode() || input.isNull()) {
                args = "";
            } else {
                try {
                    args = mapper.writeValueAsString(input);
                } catch (IOException e) {
                    args = input.toString();
                }
            }

            String output = "";
            String state = "SUCCESS";
            JsonNode result = tcId.isEmpty() ? null : toolResultsByCallId.get(tcId);
            if (result != null) {
                output = result.path("output").asText("");
                // 持久化的 tool_result 没有显式 state 字段；如被截断则在输出末尾加省略号
                if (result.path("truncated").asBoolean(false) && !output.isEmpty()) {
                    output = output + "\n…[truncated]";
                }
            }
            tcs.add(new ToolCallDto(tcId, name, args, output, state));
        }
        return tcs;
    }

    /**
     * 寻找 sessions 目录：
     * workspaceRoot/<agentId>/<name>/agents/<name>/sessions/
     * 兼容 name != id 的情形：用 walk 找任意含 *.jsonl 的 sessions 子目录。
     */
    private Path findSessionsDir(String agentId) {
        Path root = registry.getWorkspacePath(agentId);
        if (!Files.isDirectory(root)) return null;

        // 快速路径：按约定名直接定位
        String agentName = registry.findById(agentId)
                .map(s -> s.getName())
                .orElse(agentId);
        Path conventional = root.resolve(agentName).resolve("agents").resolve(agentName).resolve("sessions");
        if (Files.isDirectory(conventional)) return conventional;

        // 兜底：walk 5 层内找名为 sessions 且含 .jsonl 的目录
        try (var stream = Files.walk(root, 5)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().equals("sessions"))
                    .filter(p -> {
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.jsonl")) {
                            return ds.iterator().hasNext();
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Walk failed for {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    private SessionInfo readSessionMeta(Path file) {
        String sessionId = stripExt(file.getFileName().toString());
        int messageCount = 0;
        long lastActive = 0;
        String firstUserContent = null;
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                if (line.isBlank()) continue;
                JsonNode n = mapper.readTree(line);
                String type = n.path("type").asText("");
                if (!"message".equals(type)) continue;
                String role = n.path("role").asText("");
                long ts = n.path("timestamp").asLong(0);
                if (ts > lastActive) lastActive = (long) ts;
                messageCount++;
                if (firstUserContent == null && "USER".equalsIgnoreCase(role)) {
                    firstUserContent = n.path("content").asText("");
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read session meta {}: {}", file, e.getMessage());
            return null;
        }
        String preview = firstUserContent == null ? null : previewOf(firstUserContent);
        return new SessionInfo(sessionId, messageCount, lastActive, preview);
    }

    /** 取首条 USER 消息前 9 个 codePoint 作为侧栏展示标题 */
    private static String previewOf(String content) {
        String display = content
                .replaceAll("(?is)<(?:think|reasoning)>.*?</(?:think|reasoning)>", "")
                .trim();
        if (display.isEmpty()) return null;
        int[] cps = display.codePoints().toArray();
        int n = Math.min(9, cps.length);
        return new String(cps, 0, n);
    }

    /** 把 jsonl 单行 message 转 ChatHistoryMessage；剥离 <think>/<reasoning> 块 */
    private ChatHistoryMessage parseMessage(JsonNode n) {
        String role = n.path("role").asText("").toUpperCase();
        String content = n.path("content").asText("");
        long timestamp = (long) n.path("timestamp").asDouble(0);

        Matcher m = THINK_PATTERN.matcher(content);
        StringBuilder thinkingSb = new StringBuilder();
        while (m.find()) thinkingSb.append(m.group(1));
        String thinking = thinkingSb.toString().trim();
        String display = m.replaceAll("").trim();
        return new ChatHistoryMessage(role, display, thinking, timestamp);
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }
}