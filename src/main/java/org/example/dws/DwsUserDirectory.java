package org.example.dws;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 计算每个 userId 对应的隔离目录：
 * <pre>
 *   <configRoot>/users/<userId>/
 *     ├── config/        ← DWS_CONFIG_DIR（dws app.json + profiles.json 落盘）
 *     └── keychain/      ← DWS_KEYCHAIN_DIR（file-DEK + ciphertext）
 * </pre>
 *
 * <p>严格校验 userId 防止路径穿越（沿用现有 SessionController 的正则风格）。
 */
@Component
public class DwsUserDirectory {

    private static final Logger log = LoggerFactory.getLogger(DwsUserDirectory.class);

    /** 与现有 AgentId / sessionId 一致的正则约束 */
    public static final Pattern USER_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final DwsProperties props;
    private final Path usersRoot;
    private final Path registryFile;

    public DwsUserDirectory(DwsProperties props) {
        this.props = props;
        Path root = Paths.get(props.getConfigRoot()).toAbsolutePath().normalize();
        this.usersRoot = root.resolve("users");
        this.registryFile = root.resolve("_registry.json");
    }

    @PostConstruct
    void init() throws IOException {
        Files.createDirectories(usersRoot);
        log.info("dws users root: {}", usersRoot);
    }

    /** 校验 userId 合法性 + 路径不外溢。 */
    public void validate(String userId) {
        if (userId == null || !USER_ID_PATTERN.matcher(userId).matches()) {
            throw new IllegalArgumentException(
                    "userId 必须匹配 ^[a-zA-Z0-9_-]{1,64}$，收到: " + userId);
        }
    }

    public Path resolveUserDir(String userId) {
        validate(userId);
        Path dir = usersRoot.resolve(userId).normalize();
        if (!dir.startsWith(usersRoot)) {
            throw new IllegalArgumentException("userDir 路径穿越: " + userId);
        }
        return dir;
    }

    /** 返回 DWS_CONFIG_DIR 指向的目录（首次调用时创建） */
    public Path configDir(String userId) throws IOException {
        Path dir = resolveUserDir(userId).resolve("config");
        Files.createDirectories(dir);
        return dir;
    }

    /** 返回 DWS_KEYCHAIN_DIR 指向的目录（首次调用时创建 0700） */
    public Path keychainDir(String userId) throws IOException {
        Path dir = resolveUserDir(userId).resolve("keychain");
        Files.createDirectories(dir);
        applyRestrictive(dir);
        return dir;
    }

    private void applyRestrictive(Path dir) {
        try {
            Set<java.nio.file.attribute.PosixFilePermission> perms = Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(dir, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / 非 POSIX FS 跳过
        }
    }

    public Path usersRoot() { return usersRoot; }
    public Path registryFile() { return registryFile; }
}
