package org.example.dws;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 探测 dws 二进制可执行文件路径。优先级：
 * <ol>
 *   <li>显式配置 {@code dws.binary-path}</li>
 *   <li>用户数据目录下的 {@code <configRoot>/bin/dws}（首次启动时下载或用户手动放置）</li>
 *   <li>系统 PATH（{@code which dws}）</li>
 * </ol>
 * 仅探测，不下载；下载逻辑在 {@link DwsBinaryDownloader} 中按需触发。
 */
@Component
public class DwsBinaryLocator {

    private static final Logger log = LoggerFactory.getLogger(DwsBinaryLocator.class);

    private final DwsProperties props;
    private final Path userDataBinary;
    private Path cached;

    public DwsBinaryLocator(DwsProperties props) {
        this.props = props;
        this.userDataBinary = Paths.get(props.getConfigRoot()).toAbsolutePath().normalize()
                .resolve("bin").resolve(props.getBinaryFilename());
    }

    @PostConstruct
    void init() {
        try {
            if (userDataBinary.getParent() != null) {
                Files.createDirectories(userDataBinary.getParent());
            }
        } catch (IOException e) {
            log.warn("Cannot create dws bin dir {}: {}", userDataBinary.getParent(), e.getMessage());
        }
    }

    /** 返回当前可用的 dws 二进制路径；找不到返回 null。 */
    public synchronized Path locate() {
        if (cached != null && Files.isExecutable(cached)) {
            return cached;
        }
        cached = doLocate();
        if (cached != null) {
            log.info("Located dws binary at {}", cached);
        } else {
            log.warn("dws binary not found; please configure dws.binary-path or install dws");
        }
        return cached;
    }

    /** userData 下的预期路径（下载器写到这）。即使当前不可执行也返回，便于下载器覆盖。 */
    public Path userDataBinaryPath() {
        return userDataBinary;
    }

    private Path doLocate() {
        // 1) explicit config
        String explicit = props.getBinaryPath();
        if (explicit != null && !explicit.isBlank()) {
            Path p = Paths.get(explicit);
            if (Files.isExecutable(p)) return p;
        }

        // 2) userData/bin/dws
        if (Files.isExecutable(userDataBinary)) {
            return userDataBinary;
        }

        // 3) PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null && !pathEnv.isEmpty()) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir.isEmpty()) continue;
                Path candidate = Paths.get(dir).resolve(props.getBinaryFilename());
                if (Files.isExecutable(candidate)) return candidate;
            }
        }

        return null;
    }

    /** 缓存失效：下载器写入新二进制后调用 */
    public synchronized void invalidate() {
        this.cached = null;
    }
}
