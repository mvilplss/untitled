package org.example.dws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * dws 调用审计日志：每次 invoke 写一行 JSONL 到 {@code .agentscope/dws-audit.jsonl}。
 * 敏感字段（accessToken / refreshToken / appSecret / cookies）写入前过滤为 {@code ***}。
 */
@Component
public class DwsAuditService {

    private static final Logger log = LoggerFactory.getLogger(DwsAuditService.class);

    /** 命中这些 key 名时值被掩码 */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "accesstoken", "refreshtoken", "appsecret", "cookie", "authorization",
            "password", "clientsecret");

    private final DwsProperties props;
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private Path auditFile;

    public DwsAuditService(DwsProperties props) {
        this.props = props;
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    void init() throws IOException {
        Path root = Paths.get(props.getConfigRoot()).toAbsolutePath().normalize().getParent();
        if (root == null) root = Paths.get(".agentscope").toAbsolutePath();
        Files.createDirectories(root);
        this.auditFile = root.resolve("dws-audit.jsonl");
        log.info("dws audit file: {}", auditFile);
    }

    public void record(String userId, String command, int exitCode, long durationMs, String note) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("ts", Instant.now().toString());
        entry.put("userId", userId);
        entry.put("command", command);
        entry.put("exitCode", exitCode);
        entry.put("durationMs", durationMs);
        if (note != null && !note.isBlank()) {
            entry.put("note", sanitize(note));
        }
        append(entry);
    }

    public void recordError(String userId, String command, String errorCode, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", Instant.now().toString());
        entry.put("userId", userId);
        entry.put("command", command);
        entry.put("error", errorCode);
        entry.put("message", sanitize(message));
        append(mapper.valueToTree(entry));
    }

    private void append(ObjectNode node) {
        synchronized (lock) {
            try {
                Files.writeString(auditFile,
                        mapper.writeValueAsString(node) + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("Failed to write dws audit: {}", e.getMessage());
            }
        }
    }

    /** 读取最近 N 条审计记录 */
    public String tail(int maxLines) {
        if (!Files.exists(auditFile)) return "";
        try {
            var lines = Files.readAllLines(auditFile);
            int from = Math.max(0, lines.size() - maxLines);
            return String.join("\n", lines.subList(from, lines.size()));
        } catch (IOException e) {
            log.warn("Failed to tail audit: {}", e.getMessage());
            return "";
        }
    }

    /** 过滤敏感字段 */
    private String sanitize(String s) {
        if (s == null) return "";
        String out = s;
        for (String k : SENSITIVE_KEYS) {
            out = Pattern.compile(
                    "(?i)(\"" + Pattern.quote(k) + "\"\\s*:\\s*\")([^\"]*)(\")")
                    .matcher(out).replaceAll("$1***$3");
            out = Pattern.compile(
                    "(?i)(\\b" + Pattern.quote(k) + "\\b\\s*=\\s*)([^\\s,&]+)")
                    .matcher(out).replaceAll("$1***");
        }
        return out;
    }

    public Path auditFile() { return auditFile; }
}
