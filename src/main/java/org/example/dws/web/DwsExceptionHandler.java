package org.example.dws.web;

import jakarta.servlet.http.HttpServletResponse;
import org.example.dws.DwsException;
import org.example.dws.web.dto.DwsError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dws 子模块的统一异常处理：把 {@link DwsException} 及其子类翻译成结构化 HTTP 响应。
 * 复用 {@code GlobalExceptionHandler} 对 {@link IllegalArgumentException} 等通用异常的兜底。
 */
@RestControllerAdvice(basePackages = "org.example.dws.web")
public class DwsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DwsExceptionHandler.class);

    @ExceptionHandler(DwsException.BinaryMissing.class)
    public ResponseEntity<Map<String, Object>> binaryMissing(DwsException.BinaryMissing e) {
        log.error("dws binary missing: {}", e.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "binary_missing", e.getMessage(),
                List.of("检查 dws.binary-path 配置 / 重启后端触发下载"), null);
    }

    @ExceptionHandler(DwsException.UserNotFound.class)
    public ResponseEntity<Map<String, Object>> userNotFound(DwsException.UserNotFound e) {
        return body(HttpStatus.UNAUTHORIZED, "user_not_found", e.getMessage(),
                List.of("检查 X-User-Id 头"), null);
    }

    @ExceptionHandler(DwsException.Unauthorized.class)
    public ResponseEntity<Map<String, Object>> unauthorized(DwsException.Unauthorized e) {
        return body(HttpStatus.UNAUTHORIZED, "unauthorized", e.getMessage(),
                List.of("POST /api/dws/auth/device 重新授权"), null);
    }

    @ExceptionHandler(DwsException.InvokeFailed.class)
    public ResponseEntity<Map<String, Object>> invokeFailed(DwsException.InvokeFailed e) {
        if (e.getExitCode() == 2) {
            return body(HttpStatus.UNAUTHORIZED, e.getCode(), e.getMessage(),
                    List.of("POST /api/dws/auth/device 重新授权"), e.getExitCode());
        }
        if (e.getExitCode() == 3) {
            return body(HttpStatus.BAD_REQUEST, e.getCode(), e.getMessage(),
                    List.of("检查参数"), e.getExitCode());
        }
        return body(HttpStatus.BAD_GATEWAY, e.getCode(), e.getMessage(), null, e.getExitCode());
    }

    @ExceptionHandler(DwsException.class)
    public ResponseEntity<Map<String, Object>> dwsGeneric(DwsException e) {
        if (e instanceof DwsException.InvokeFailed ife) return invokeFailed(ife);
        log.warn("dws error {}: {}", e.getCode(), e.getMessage());
        return body(HttpStatus.INTERNAL_SERVER_ERROR, e.getCode(), e.getMessage(), null, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> illegal(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "invalid_argument", e.getMessage(), null, null);
    }

    @ExceptionHandler(IOException.class)
    public void io(IOException e, HttpServletResponse resp) {
        log.debug("dws io error (likely client abort): {}", e.getMessage());
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String msg,
                                                     List<String> actions, Integer exitCode) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("status", status.value());
        b.put("code", code);
        b.put("error", msg);
        if (actions != null) b.put("actions", actions);
        if (exitCode != null) b.put("exitCode", exitCode);
        return ResponseEntity.status(status).body(b);
    }
}
