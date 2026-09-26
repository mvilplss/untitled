package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.AgentRegistry;
import org.example.web.dto.AttachmentDto;
import org.example.web.dto.ChatHistoryMessage;
import org.example.web.dto.SessionInfo;
import org.example.web.dto.ToolCallDto;
import org.example.web.dto.UsageDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    /** 列出某 agent 下所有 session 的元信息（跳过空 jsonl）。定时任务会话（task- 前缀）标记 isTask 一并返回。 */
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
     * 删除某 session 的 jsonl + log.jsonl 文件 + usage sidecar。
     * sessionId 必须仅含安全字符（防路径穿越）。
     * @return true 至少删除了一个文件；false 文件不存在
     */
    public boolean deleteSession(String agentId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()
                || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("invalid sessionId");
        }
        Path sessionsDir = findSessionsDir(agentId);
        boolean removed = false;
        if (sessionsDir != null) {
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
        }
        // usage sidecar（独立于 sessions 目录，每个 agent 各自一份）
        Path usageFile = usageFile(agentId, sessionId);
        try {
            if (Files.deleteIfExists(usageFile)) {
                removed = true;
                log.info("Deleted usage file: {}", usageFile);
            }
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", usageFile, e.getMessage());
        }
        // attachments sidecar
        Path attFile = attachmentsFile(agentId, sessionId);
        try {
            if (Files.deleteIfExists(attFile)) {
                removed = true;
                log.info("Deleted attachments file: {}", attFile);
            }
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", attFile, e.getMessage());
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

        // ===== Pass 3: 把 usage sidecar 的 LLM 调用统计挂回 ASSISTANT 消息 =====
        attachUsages(agentId, sessionId, out);
        // ===== Pass 4: 把 attachments sidecar 挂回 USER 消息 =====
        attachAttachments(agentId, sessionId, out);
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
        boolean isTask = sessionId.startsWith(TASK_SESSION_PREFIX);
        String preview = firstUserContent == null ? null : previewOf(firstUserContent, isTask);
        return new SessionInfo(sessionId, messageCount, lastActive, preview, isTask);
    }

    /** 任务会话 id 前缀（与 TaskExecutionService.SESSION_PREFIX 对齐）。 */
    private static final String TASK_SESSION_PREFIX = "task-";

    /** 任务 prompt 前缀里抽任务名：【定时任务「<name>」自动执行】 */
    private static final Pattern TASK_NAME_PATTERN = Pattern.compile("^【定时任务「(.+?)」自动执行】");

    /**
     * 取侧栏展示标题。
     * 普通会话：前 9 个 codePoint。
     * 任务会话（isTask=true）：从 prompt 前缀里抽出任务名（如「慢测试2」）；抽不到则 fallback 到普通逻辑。
     */
    private static String previewOf(String content, boolean isTask) {
        String display = content
                .replaceAll("(?is)<(?:think|reasoning)>.*?</(?:think|reasoning)>", "")
                .trim();
        if (display.isEmpty()) return null;
        if (isTask) {
            Matcher m = TASK_NAME_PATTERN.matcher(display);
            if (m.find()) {
                return m.group(1);
            }
        }
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

    // ==================== Usage sidecar ====================

    /** usage 落盘目录：workspaceRoot/<agentId>/usage/<sessionId>.usage.jsonl（每行一条 UsageDto JSON） */
    private Path usageFile(String agentId, String sessionId) {
        return registry.getWorkspacePath(agentId).resolve("usage").resolve(sessionId + ".usage.jsonl");
    }

    /** 校验 sessionId 字符（防路径穿越） */
    private static boolean isValidSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        if (sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) return false;
        return true;
    }

    /**
     * 追加一条 LLM 调用 usage 记录到 sidecar。
     * AgentService 在收到 ModelCallEndEvent 时按模型调用顺序写入。
     */
    public void appendUsage(String agentId, String sessionId, UsageDto usage) {
        if (!isValidSessionId(sessionId) || usage == null) return;
        try {
            Path dir = registry.getWorkspacePath(agentId).resolve("usage");
            Files.createDirectories(dir);
            Path file = dir.resolve(sessionId + ".usage.jsonl");
            String line = mapper.writeValueAsString(usage) + "\n";
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to append usage for {}/{}: {}", agentId, sessionId, e.getMessage());
        }
    }

    /** 读取某 session 全部 usage 记录（按写入顺序）。文件不存在或为空返回空列表。 */
    public List<UsageDto> readUsages(String agentId, String sessionId) {
        if (!isValidSessionId(sessionId)) return Collections.emptyList();
        Path file = usageFile(agentId, sessionId);
        if (!Files.isRegularFile(file)) return Collections.emptyList();
        List<UsageDto> result = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    result.add(mapper.readValue(line, UsageDto.class));
                } catch (IOException e) {
                    // 单行解析失败跳过（保留其他记录）
                    log.warn("Failed to parse usage line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read usages {}/{}: {}", agentId, sessionId, e.getMessage());
        }
        return result;
    }

    /**
     * 把 sidecar 中的 usage 记录按模型调用顺序挂回 ASSISTANT 消息。
     * 匹配规则（顺序遍历）：
     * <ul>
     *   <li>createsEntry=true 的记录依次挂到 ASSISTANT 消息序列（按序 1:1）</li>
     *   <li>createsEntry=false（纯 tool_use 无文本的调用）的记录挂到上一条已分配的 ASSISTANT 消息
     *       —— 与现有 tool_use/tool_result 回挂逻辑一致（它们的 parentId 也链回上一条）</li>
     *   <li>多余 usage（无对应消息）静默丢弃</li>
     *   <li>无 sidecar 数据 → 不影响历史展示</li>
     * </ul>
     */
    private void attachUsages(String agentId, String sessionId, List<ChatHistoryMessage> out) {
        List<UsageDto> usages = readUsages(agentId, sessionId);
        if (usages.isEmpty()) return;

        List<ChatHistoryMessage> assistants = new ArrayList<>();
        for (ChatHistoryMessage m : out) {
            if ("ASSISTANT".equalsIgnoreCase(m.getRole())) assistants.add(m);
        }
        if (assistants.isEmpty()) return;

        int mi = 0;
        List<UsageDto> pending = new ArrayList<>();
        for (UsageDto u : usages) {
            if (u.isCreatesEntry()) {
                if (mi < assistants.size()) {
                    ChatHistoryMessage target = assistants.get(mi++);
                    List<UsageDto> merged = new ArrayList<>(pending);
                    pending.clear();
                    merged.add(u);
                    target.setUsages(merged);
                }
                // 超过 ASSISTANT 消息数的（subagent / 摘要等溢出）静默丢弃
            } else {
                if (mi > 0) {
                    // 挂到上一条已匹配的 ASSISTANT 消息（与 tool_use 的 parentId 回挂逻辑一致）
                    assistants.get(mi - 1).getUsages().add(u);
                } else {
                    pending.add(u);
                }
            }
        }
        // 若 pending 中残留（极少见：首次调用就是纯 tool_use），归并到第一条 ASSISTANT
        if (!pending.isEmpty() && !assistants.isEmpty()) {
            assistants.get(0).getUsages().addAll(pending);
        }
    }

    // ==================== Attachments sidecar ====================

    /** attachments 落盘目录：workspaceRoot/<agentId>/usage/<sessionId>.attachments.jsonl（每行一条 JSON {timestamp, attachments[]}） */
    private Path attachmentsFile(String agentId, String sessionId) {
        return registry.getWorkspacePath(agentId).resolve("usage").resolve(sessionId + ".attachments.jsonl");
    }

    /**
     * 追加一条 USER 消息的附件记录到 sidecar。
     * 用时间戳与 USER message 的 timestamp 对齐（近似匹配）。
     */
    public void appendAttachments(String agentId, String sessionId, long timestamp, List<AttachmentDto> attachments) {
        if (!isValidSessionId(sessionId) || attachments == null || attachments.isEmpty()) return;
        try {
            Path dir = registry.getWorkspacePath(agentId).resolve("usage");
            Files.createDirectories(dir);
            Path file = dir.resolve(sessionId + ".attachments.jsonl");
            // {ts, attachments: [...]} 每行一条
            com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
            node.put("ts", timestamp);
            node.set("attachments", mapper.valueToTree(attachments));
            String line = mapper.writeValueAsString(node) + "\n";
            Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to append attachments for {}/{}: {}", agentId, sessionId, e.getMessage());
        }
    }

    /** 读取某 session 全部附件记录（按写入顺序）。每条为 {ts, attachments[]}。 */
    private List<JsonNode> readAttachmentEntries(String agentId, String sessionId) {
        if (!isValidSessionId(sessionId)) return Collections.emptyList();
        Path file = attachmentsFile(agentId, sessionId);
        if (!Files.isRegularFile(file)) return Collections.emptyList();
        List<JsonNode> result = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    result.add(mapper.readTree(line));
                } catch (IOException e) {
                    log.warn("Failed to parse attachments line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read attachments {}/{}: {}", agentId, sessionId, e.getMessage());
        }
        return result;
    }

    /**
     * 把 sidecar 中的附件记录挂回 USER 消息。
     * 匹配规则：按 sidecar 写入顺序与 USER 消息出现顺序 1:1 对齐。
     */
    private void attachAttachments(String agentId, String sessionId, List<ChatHistoryMessage> out) {
        List<JsonNode> entries = readAttachmentEntries(agentId, sessionId);
        if (entries.isEmpty()) return;

        List<ChatHistoryMessage> users = new ArrayList<>();
        for (ChatHistoryMessage m : out) {
            if ("USER".equalsIgnoreCase(m.getRole())) users.add(m);
        }
        if (users.isEmpty()) return;

        int ui = 0;
        for (JsonNode entry : entries) {
            if (ui >= users.size()) break;
            JsonNode atts = entry.path("attachments");
            if (atts.isArray() && atts.size() > 0) {
                List<AttachmentDto> list = new ArrayList<>();
                for (JsonNode a : atts) {
                    try {
                        list.add(mapper.treeToValue(a, AttachmentDto.class));
                    } catch (IOException ignored) {}
                }
                if (!list.isEmpty()) users.get(ui).setAttachments(list);
            }
            ui++;
        }
    }
}