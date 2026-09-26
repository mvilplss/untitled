package org.example.sandbox.dto;

/**
 * 沙箱文件浏览 API DTO 集合。
 *
 * <p>目录项 name 取 path 末段（与 ls -F 行为一致，方便前端 el-tree 直接当 label 用）；
 * path 是容器内绝对路径（以 / 开头）；size/mtime 是 best-effort，缺失时为 0 / 空串。
 */
public final class SandboxFileDtos {

    private SandboxFileDtos() {}

    /** 目录列表响应。 */
    public record FileListResponse(String path, java.util.List<FileEntry> entries) {}

    /** 单个文件 / 目录条目。 */
    public record FileEntry(String name, String path, boolean isDirectory, long size, String modifiedAt) {}

    /**
     * 文件内容响应。
     *
     * <p>{@code encoding} 取值：
     * <ul>
     *   <li>{@code "utf-8"} —— 文本文件直读（受 limit 行数限制）</li>
     *   <li>{@code "base64"} —— 二进制文件 base64 编码（用于前端的 image / download 端点之外的场景）</li>
     * </ul>
     *
     * <p>{@code truncated=true} 表示行数触达 {@code limit}；前端应提示「加载更多」或直接走 raw 下载。
     * {@code tooLarge=true} 表示文件超过预览阈值（默认 2MB），前端应直接走 raw 端点下载。
     */
    public record FileContentResponse(
            String path,
            String encoding,
            String content,
            long size,
            boolean truncated,
            boolean tooLarge) {}

    /** 沙箱运行状态：用于前端抽屉打开时决定是否显示「启动沙箱」按钮。 */
    public record SandboxStatusResponse(
            boolean running,
            String workspaceRoot,
            String containerId,
            String agentId) {}
}
