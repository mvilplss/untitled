package org.example.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.example.config.AgentProperties;
import org.example.util.MimeUtils;
import org.example.web.dto.AttachmentDto;
import org.example.web.dto.ChatUploadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聊天附件上传。
 * <p>上传到宿主机 {@code ~/.agentscope/chat-uploads/<agentId>/}，沙箱容器通过
 * bind mount 在 {@code /workspace/uploads/} 看到同一批文件（Agent 可在沙箱里
 * shell / read / dws 操作）。图片额外支持走多模态（base64 inline 到 LLM）。
 */
@RestController
@RequestMapping("/api/agents/{id}/chat")
@Tag(name = "聊天附件", description = "上传聊天附件（图片/PDF/文档）到沙箱 uploads 目录")
public class ChatUploadController {

    private static final Logger log = LoggerFactory.getLogger(ChatUploadController.class);

    /** 图片单文件上限 10MB */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    /** 其他文件单文件上限 50MB */
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    private final AgentProperties props;

    public ChatUploadController(AgentProperties props) {
        this.props = props;
    }

    @Operation(
            summary = "上传聊天附件",
            description = "multipart/form-data 上传多个文件，返回 {id,name,path,size,mime,containerPath}。"
                    + "图片上限 10MB，其他 50MB。文件存到 host "
                    + "{agent.chatUploadsRoot}/<agentId>/<uuid>-<name>，"
                    + "沙箱通过 bind mount 在 /workspace/uploads/ 看到同一批文件。"
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200", description = "OK"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400", description = "文件超出大小限制或类型不允许",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            schema = @io.swagger.v3.oas.annotations.media.Schema(ref = "ErrorResponse")))
    })
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatUploadResponse upload(
            @Parameter(description = "Agent ID", example = "poet", required = true)
            @PathVariable("id") String agentId,
            @Parameter(description = "用户标识", example = "alice")
            @RequestParam(value = "userId", required = false, defaultValue = "anonymous") String userId,
            @Parameter(description = "会话 ID（用于路径隔离）", example = "default")
            @RequestParam(value = "sessionId", required = false, defaultValue = "default") String sessionId,
            @Parameter(description = "待上传的文件（可多个）", required = true)
            @RequestParam("files") List<MultipartFile> files) {

        // 解析上传根目录
        String uploadsRoot = props.getSandbox().getChatUploadsRoot();
        Path baseDir = expandHome(uploadsRoot).resolve(agentId).resolve(userId);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建上传目录: " + baseDir, e);
        }

        List<AttachmentDto> result = new ArrayList<>();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            long size = f.getSize();
            String origName = MimeUtils.sanitizeFilename(f.getOriginalFilename());
            String mime = MimeUtils.guessMediaType(origName).toString();
            boolean isImg = MimeUtils.isImage(mime);

            // 大小校验
            long limit = isImg ? MAX_IMAGE_BYTES : MAX_FILE_BYTES;
            if (size > limit) {
                throw new IllegalArgumentException(
                        "文件 " + origName + " 超出大小限制: " + size + " > " + limit + " bytes");
            }

            // 写到磁盘：<name>（重复时自动加 (N) 序号）
            String id = UUID.randomUUID().toString().replace("-", "");
            String storedName = resolveNameCollision(baseDir, origName);
            Path dest = baseDir.resolve(storedName);
            try {
                f.transferTo(dest.toFile());
            } catch (IOException e) {
                throw new IllegalStateException("写入附件失败: " + dest, e);
            }

            // 容器内路径（chat send 时通过 docker cp 传入沙箱）
            String containerPath = "/workspace/uploads/" + userId + "/" + storedName;

            result.add(new AttachmentDto(id, origName, dest.toAbsolutePath().toString(), size, mime, containerPath));
            log.debug("Uploaded chat attachment: agentId={}, userId={}, name={}, storedName={}, size={}, mime={}",
                    agentId, userId, origName, storedName, size, mime);
        }
        return new ChatUploadResponse(result);
    }

    /**
     * 文件名去重：若原名已存在，追加 " (1)"、" (2)" 等序号（Windows 风格）。
     * 极端情况下（超过 1000 次冲突）加时间戳兜底。
     */
    private static String resolveNameCollision(Path dir, String origName) {
        Path candidate = dir.resolve(origName);
        if (!Files.exists(candidate)) return origName;

        int dot = origName.lastIndexOf('.');
        String base = dot >= 0 ? origName.substring(0, dot) : origName;
        String ext = dot >= 0 ? origName.substring(dot) : "";

        for (int i = 1; i < 1000; i++) {
            String name = base + " (" + i + ")" + ext;
            if (!Files.exists(dir.resolve(name))) return name;
        }
        return base + "_" + System.currentTimeMillis() + ext;
    }

    private static Path expandHome(String p) {
        if (p == null) return Paths.get(".");
        if (p.startsWith("~")) {
            String home = System.getProperty("user.home");
            return Paths.get(home + p.substring(1)).toAbsolutePath().normalize();
        }
        return Paths.get(p).toAbsolutePath().normalize();
    }
}
