package org.example.dws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 对 dws 二进制的子进程封装。
 *
 * <p>关键能力：
 * <ul>
 *   <li>进程级隔离：每个 userId 一个独立 env 变量集（DWS_CONFIG_DIR / DWS_KEYCHAIN_DIR /
 *       DWS_DISABLE_KEYCHAIN=1）和独立 cwd</li>
 *   <li>早停（early-stop）：stdout 累计到包含指定短语时立即 destroyForcibly 并返回当前 stdout
 *       —— 用于 device-flow 场景捕 userCode</li>
 *   <li>输出限制：stdout 字节数累计超过 {@code dws.max-output-bytes} 立即终止进程</li>
 *   <li>超时：默认 {@code dws.default-timeout-ms}，可每次覆盖</li>
 *   <li>PATH 透传：保留系统 PATH 以便 dws 找到自身依赖</li>
 * </ul>
 */
@Component
public class DwsInvoker {

    private static final Logger log = LoggerFactory.getLogger(DwsInvoker.class);

    private final DwsProperties props;
    private final DwsBinaryLocator locator;

    public DwsInvoker(DwsProperties props, DwsBinaryLocator locator) {
        this.props = props;
        this.locator = locator;
    }

    /**
     * 执行 dws 子命令（带早停）。
     *
     * @param userId  本地用户标识（决定 DWS_CONFIG_DIR / DWS_KEYCHAIN_DIR）
     * @param args    要传给 dws 的参数（不含 dws 路径本身）
     * @param env     额外覆盖或新增的环境变量；DWS_CONFIG_DIR / DWS_KEYCHAIN_DIR /
     *                DWS_DISABLE_KEYCHAIN 会被本次调用的 userId 强制覆盖（最后写）
     * @param cwd     子进程工作目录；null 则用 userId 的 config 目录
     * @param timeoutMs 超时（毫秒）；<=0 走 props 默认值
     * @param earlyStopPhrase 当 stdout 累计到包含此字符串时立即 destroyForcibly 进程
     *                        并返回当前 stdout。null 表示不启用早停
     */
    public Result run(String userId, List<String> args, Map<String, String> env,
                      Path cwd, long timeoutMs, String earlyStopPhrase) {
        Path binary = locator.locate();
        if (binary == null) {
            throw new DwsException.BinaryMissing("dws 二进制不可用，请确认已安装或启动时下载成功");
        }

        long effectiveTimeout = timeoutMs > 0 ? timeoutMs : props.getDefaultTimeoutMs();
        int maxBytes = props.getMaxOutputBytes();

        Path configDir;
        Path keychainDir;
        Path effectiveCwd;
        try {
            configDir = resolveUserSubdir(userId, "config");
            keychainDir = resolveUserSubdir(userId, "keychain");
            effectiveCwd = cwd != null ? cwd : configDir;
        } catch (IOException e) {
            throw new DwsException("dir_create_failed",
                    "无法创建用户隔离目录: " + e.getMessage(), e);
        }

        List<String> cmd = new ArrayList<>(args.size() + 1);
        cmd.add(binary.toString());
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(effectiveCwd.toFile());

        // 1) 继承系统环境（含 PATH）
        pb.environment().putAll(System.getenv());
        // 2) 调用方 env
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }
        // 3) userId 强隔离旋钮（最后写以避免被 env 误覆盖）
        pb.environment().put("DWS_CONFIG_DIR", configDir.toString());
        pb.environment().put("DWS_KEYCHAIN_DIR", keychainDir.toString());
        pb.environment().put("DWS_DISABLE_KEYCHAIN", "1");

        long start = System.currentTimeMillis();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new DwsException("spawn_failed",
                    "dws 子进程启动失败: " + e.getMessage(), e);
        }

        // 并发 drain stdout / stderr
        LimitedReader out = new LimitedReader(process.getInputStream(), maxBytes);
        LimitedReader err = new LimitedReader(process.getErrorStream(), maxBytes);
        Thread tOut = new Thread(out, "dws-stdout-" + process.pid());
        Thread tErr = new Thread(err, "dws-stderr-" + process.pid());
        tOut.setDaemon(true);
        tErr.setDaemon(true);
        tOut.start();
        tErr.start();

        log.debug("dws exec pid={} args={} timeoutMs={} earlyStop={}",
                process.pid(), args, effectiveTimeout,
                earlyStopPhrase == null ? "<off>" : earlyStopPhrase);

        boolean earlyStopped = false;
        long deadline = System.currentTimeMillis() + effectiveTimeout;

        try {
            while (true) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;

                // 早停检测
                if (earlyStopPhrase != null && !earlyStopPhrase.isEmpty()) {
                    String cur = out.peek();
                    if (cur.contains(earlyStopPhrase)) {
                        log.debug("dws early-stop triggered for pid={}", process.pid());
                        process.destroyForcibly();
                        earlyStopped = true;
                        break;
                    }
                }

                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new DwsException("invoker_interrupted", "dws 等待被中断", ie);
        }

        if (!earlyStopped && process.isAlive()) {
            process.destroyForcibly();
            try { tOut.join(200); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            try { tErr.join(200); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            throw new DwsException("invoke_timeout",
                    "dws 子命令超时（" + effectiveTimeout + "ms）: " + args);
        }

        try { tOut.join(500); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try { tErr.join(500); } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        long durationMs = System.currentTimeMillis() - start;
        Integer exit = process.isAlive() ? null : process.exitValue();
        String stdoutStr = out.getResult();
        String stderrStr = err.getResult();

        if (out.isTruncated() || err.isTruncated()) {
            log.warn("dws output truncated args={} maxBytes={}", args, maxBytes);
        }

        if (exit != null && exit != 0) {
            log.warn("dws non-zero exit={} args={} stderr={}", exit, args, truncate(stderrStr));
            throw new DwsException.InvokeFailed(exit, truncate(stderrStr));
        }

        // 早停时 exit 为 null，按成功处理（调用方已经从 stdout 拿到了所需信息）
        return new Result(exit == null ? 0 : exit, stdoutStr, stderrStr, durationMs);
    }

    /** 兼容旧签名：不启用早停 */
    public Result run(String userId, List<String> args, Map<String, String> env,
                      Path cwd, long timeoutMs) {
        return run(userId, args, env, cwd, timeoutMs, null);
    }

    private Path resolveUserSubdir(String userId, String sub) throws IOException {
        Path dir = Paths.get(props.getConfigRoot()).toAbsolutePath().normalize()
                .resolve("users").resolve(userId).resolve(sub);
        Files.createDirectories(dir);
        return dir;
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }

    /** 子进程结果 */
    public static class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final long durationMs;

        public Result(int exitCode, String stdout, String stderr, long durationMs) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.durationMs = durationMs;
        }
    }

    /**
     * 读 InputStream 到字节上限（防止 OOM），达上限后 stop 但仍继续 drain 残余数据。
     */
    private static class LimitedReader implements Runnable {
        private final InputStream in;
        private final int maxBytes;
        private final StringBuilder sb = new StringBuilder();
        private volatile boolean truncated = false;
        private volatile boolean done = false;

        LimitedReader(InputStream in, int maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (sb.length() + n > maxBytes) {
                        int allowed = maxBytes - sb.length();
                        if (allowed > 0) {
                            sb.append(new String(buf, 0, allowed, StandardCharsets.UTF_8));
                        }
                        truncated = true;
                        // 继续 drain 残余（不写入）让进程正常结束
                        int drained = 0;
                        while (in.read(buf) != -1) {
                            drained++;
                            if (drained > 1024) break;
                        }
                        break;
                    }
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // pipe 关闭正常
            } finally {
                done = true;
            }
        }

        String getResult() { return sb.toString(); }
        boolean isTruncated() { return truncated; }
        boolean isDone() { return done; }
        /** 实时拿到当前累计内容（线程安全快照） */
        synchronized String peek() { return sb.toString(); }
    }
}
