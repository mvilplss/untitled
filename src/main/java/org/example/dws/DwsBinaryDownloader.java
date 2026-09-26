package org.example.dws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * 首次启动或显式触发时，自动从 GitHub Releases 下载 dws 二进制到 {@code <userData>/bin/dws}。
 *
 * <p>下载策略：
 * <ul>
 *   <li>asset 名格式：{@code dws-<os>-<arch>.tar.gz}（goreleaser 产物）</li>
 *   <li>下载后手动解压 tar，提取名为 {@code dws} 的 entry</li>
 *   <li>下载后 chmod 0700（POSIX 平台）</li>
 * </ul>
 */
@Component
public class DwsBinaryDownloader {

    private static final Logger log = LoggerFactory.getLogger(DwsBinaryDownloader.class);

    private final DwsProperties props;
    private final DwsBinaryLocator locator;
    private final HttpClient http;

    public DwsBinaryDownloader(DwsProperties props, DwsBinaryLocator locator) {
        this.props = props;
        this.locator = locator;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 探测到二进制可用则直接返回；否则尝试下载；下载失败抛 {@link DwsException.BinaryMissing}。
     * 由 ApplicationRunner 在 Spring 启动后调用一次。
     */
    public Path ensurePresent() {
        Path located = locator.locate();
        if (located != null) return located;

        Path target = locator.userDataBinaryPath();
        log.info("dws binary not found, attempting to download version {} to {}",
                props.getVersion(), target);

        try {
            download(target);
            locator.invalidate();
            Path after = locator.locate();
            if (after == null) {
                throw new DwsException.BinaryMissing(
                        "下载完成后 dws 二进制不可执行，请检查文件权限: " + target);
            }
            log.info("dws binary ready: {}", after);
            return after;
        } catch (Exception e) {
            throw new DwsException.BinaryMissing(
                    "无法定位或下载 dws 二进制（version=" + props.getVersion()
                            + "）：" + e.getMessage());
        }
    }

    private void download(Path target) throws IOException, InterruptedException {
        Platform p = detectPlatform();
        String assetName = p.assetName();
        String url = props.getDownloadBaseUrl() + "/" + props.getVersion() + "/" + assetName;
        log.info("Downloading dws from {}", url);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("HTTP " + resp.statusCode() + " when downloading " + url);
        }

        if (assetName.endsWith(".tar.gz")) {
            Path archive = target.resolveSibling(target.getFileName() + ".tar.gz");
            try (InputStream in = resp.body()) {
                Files.copy(in, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            extractBinaryFromTarGz(archive, target);
            Files.deleteIfExists(archive);
        } else if (assetName.endsWith(".gz")) {
            Path tmp = target.resolveSibling(target.getFileName() + ".gz");
            try (InputStream in = resp.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(tmp))) {
                Path out = target.resolveSibling(target.getFileName() + ".tmp");
                Files.copy(gz, out, StandardCopyOption.REPLACE_EXISTING);
                moveAtomically(out, target);
            }
            Files.deleteIfExists(tmp);
        } else {
            Path tmp = target.resolveSibling(target.getFileName() + ".download");
            try (InputStream in = resp.body()) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            moveAtomically(tmp, target);
        }

        applyExecutable(target);
    }

    private void extractBinaryFromTarGz(Path archive, Path target) throws IOException {
        // 提取名为 "dws"（不带目录前缀）的普通文件
        try (var fis = Files.newInputStream(archive);
             var gz = new GZIPInputStream(fis);
             var bis = new java.io.BufferedInputStream(gz)) {
            java.io.ByteArrayOutputStream entryBuf = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] header = new byte[512];
            while (true) {
                int read = bis.read(header);
                if (read < 0) break;
                if (read == 0) continue;
                String name = new String(header, 0, 100, java.nio.charset.StandardCharsets.UTF_8).trim();
                String sizeStr = new String(header, 124, 12, java.nio.charset.StandardCharsets.UTF_8).trim();
                long size = 0;
                try { size = Long.parseLong(sizeStr, 8); } catch (NumberFormatException ignored) {}

                // 空 header 表示 tar 结束
                if (name.isEmpty() && size == 0) {
                    break;
                }

                entryBuf.reset();
                long remaining = size;
                byte[] buf = new byte[8192];
                while (remaining > 0) {
                    int n = bis.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n < 0) break;
                    entryBuf.write(buf, 0, n);
                    remaining -= n;
                }
                // skip padding to 512
                long pad = (size % 512 == 0) ? 0 : (512 - (size % 512));
                if (pad > 0) bis.skip(pad);

                // 提取命名为 "dws" 的普通文件
                String base = name.contains("/") ? name.substring(name.lastIndexOf('/') + 1) : name;
                if ("dws".equals(base) && size > 0) {
                    Path tmp = target.resolveSibling(target.getFileName() + ".extract");
                    Files.write(tmp, entryBuf.toByteArray());
                    moveAtomically(tmp, target);
                    return;
                }
            }
        }
        throw new IOException("tar archive does not contain a 'dws' entry");
    }

    private void moveAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void applyExecutable(Path p) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return;
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE);
            Files.setPosixFilePermissions(p, perms);
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统跳过
        }
    }

    private Platform detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean isWin = os.contains("win");
        boolean isMac = os.contains("mac") || os.contains("darwin");
        boolean isLinux = os.contains("linux") || os.contains("nix");

        String archToken;
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            archToken = "arm64";
        } else if (arch.contains("x86_64") || arch.contains("amd64")) {
            archToken = "amd64";
        } else {
            throw new DwsException.BinaryMissing("unsupported arch: " + arch);
        }

        if (isMac) {
            return new Platform("darwin", archToken, "dws-darwin-" + archToken + ".tar.gz");
        }
        if (isLinux) {
            return new Platform("linux", archToken, "dws-linux-" + archToken + ".tar.gz");
        }
        if (isWin) {
            return new Platform("windows", archToken, "dws-windows-" + archToken + ".zip");
        }
        throw new DwsException.BinaryMissing("unsupported OS: " + os);
    }

    private record Platform(String os, String arch, String assetName) {}
}
