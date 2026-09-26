package org.example.dws;

/**
 * dws 集成专用异常体系。所有错误最终由 {@link org.example.dws.web.DwsExceptionHandler}
 * 统一翻译为带结构化字段的 HTTP 响应。
 */
public class DwsException extends RuntimeException {

    /** 机器可读的错误码，前端可用于分支判断 */
    private final String code;

    /** 面向用户的友好提示 */
    private final String hint;

    public DwsException(String code, String message) {
        super(message);
        this.code = code;
        this.hint = message;
    }

    public DwsException(String code, String message, String hint) {
        super(message);
        this.code = code;
        this.hint = hint;
    }

    public DwsException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.hint = message;
    }

    public String getCode() { return code; }
    public String getHint() { return hint; }

    /** 二进制缺失：启动时未找到 dws 且下载失败 */
    public static class BinaryMissing extends DwsException {
        public BinaryMissing(String message) {
            super("binary_missing", message);
        }
    }

    /** 用户不存在：userId 未注册 */
    public static class UserNotFound extends DwsException {
        public UserNotFound(String message) {
            super("user_not_found", message);
        }
    }

    /** 用户未授权：profile 列表为空 */
    public static class Unauthorized extends DwsException {
        public Unauthorized(String message) {
            super("unauthorized", message);
        }
    }

    /** dws 调用失败（含非零退出码） */
    public static class InvokeFailed extends DwsException {
        private final int exitCode;
        private final String stderr;

        public InvokeFailed(int exitCode, String stderr) {
            super("invoke_failed", "dws exit " + exitCode + ": " + stderr);
            this.exitCode = exitCode;
            this.stderr = stderr;
        }

        public int getExitCode() { return exitCode; }
        public String getStderr() { return stderr; }
    }
}
