package org.example.sandbox;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.sandbox.SandboxFileService.DownloadedFile;
import org.example.sandbox.dto.SandboxFileDtos.FileContentResponse;
import org.example.sandbox.dto.SandboxFileDtos.FileListResponse;
import org.example.sandbox.dto.SandboxFileDtos.SandboxStatusResponse;
import org.example.util.MimeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 沙箱文件系统浏览器 REST 端点（只读 + 显式唤醒）。
 *
 * <p>所有访问限制在沙箱 workspaceRoot（默认 /workspace）内，路径必须是 / 开头的绝对路径，
 * 不允许 .. 跳转。浏览操作共享 chat 路径的容器（USER scope 隔离），空闲 5 分钟后由
 * {@link SandboxKeepAliveManager} 自动销毁——下次访问走 /sandbox/wake 重新拉起。
 */
@RestController
@RequestMapping("/api/agents/{id}/sandbox")
@Tag(name = "沙箱文件浏览", description = "浏览数字人沙箱容器内的文件（只读 + 唤醒）")
public class SandboxFileController {

    private final SandboxFileService service;

    public SandboxFileController(SandboxFileService service) {
        this.service = service;
    }

    @GetMapping("/status")
    @Operation(summary = "探测沙箱状态",
            description = "仅读持久化 state 判断沙箱是否存在（不 acquire、不 docker I/O）。")
    public SandboxStatusResponse status(
            @PathVariable("id") String agentId,
            @Parameter(description = "USER scope 隔离 key，区分多用户沙箱", required = true)
            @RequestParam("userId") String userId) {
        return service.status(agentId, userId);
    }

    @GetMapping("/files")
    @Operation(summary = "列目录（懒加载树节点）",
            description = "返回容器内绝对路径下的目录条目。路径必须以 / 开头、落在 workspaceRoot 内、无 ..。")
    public FileListResponse list(
            @PathVariable("id") String agentId,
            @RequestParam("userId") String userId,
            @RequestParam(value = "sessionId", required = false, defaultValue = "") String sessionId,
            @RequestParam("path") String path) {
        return service.list(agentId, userId, sessionId, path);
    }

    @GetMapping("/files/content")
    @Operation(summary = "读文件内容（文本/代码预览）",
            description = "返回 utf-8 / base64 内容；limit 行数默认 2000。超 2MB 不内联，仅返回 tooLarge=true。")
    public FileContentResponse content(
            @PathVariable("id") String agentId,
            @RequestParam("userId") String userId,
            @RequestParam(value = "sessionId", required = false, defaultValue = "") String sessionId,
            @RequestParam("path") String path,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset,
            @RequestParam(value = "limit", required = false, defaultValue = "2000") int limit) {
        return service.content(agentId, userId, sessionId, path, offset, limit);
    }

    @GetMapping("/files/raw")
    @Operation(summary = "读文件原始字节（图片预览/下载）",
            description = "按扩展名推断 MIME 类型；Content-Disposition: inline 用于图片预览、attachment 用于下载。")
    public ResponseEntity<byte[]> raw(
            @PathVariable("id") String agentId,
            @RequestParam("userId") String userId,
            @RequestParam(value = "sessionId", required = false, defaultValue = "") String sessionId,
            @RequestParam("path") String path,
            @RequestParam(value = "download", required = false, defaultValue = "false") boolean download) {
        DownloadedFile f = service.download(agentId, userId, sessionId, path);
        String disposition = (download ? "attachment" : "inline")
                + "; filename=\"" + MimeUtils.sanitizeFilename(f.name()) + "\"";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MimeUtils.guessMediaType(f.name()))
                .body(f.bytes());
    }

    @PostMapping("/wake")
    @Operation(summary = "显式唤醒沙箱",
            description = "若持久化 state 存在 → Priority 3 resume + start 自愈；缺失时由 Priority 4 新建容器。")
    public SandboxStatusResponse wake(
            @PathVariable("id") String agentId,
            @RequestParam("userId") String userId,
            @RequestParam(value = "sessionId", required = false, defaultValue = "") String sessionId) {
        return service.wake(agentId, userId, sessionId);
    }

}
