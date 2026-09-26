package org.example.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "聊天附件引用（上传后返回，用于后续消息引用）")
public class AttachmentDto {

    @Schema(description = "附件唯一 ID", example = "a1b2c3d4")
    private String id;

    @Schema(description = "原始文件名", example = "photo.png")
    private String name;

    @Schema(description = "宿主机存储路径", example = "/home/user/.agentscope/chat-uploads/agent1/xxx-photo.png")
    private String path;

    @Schema(description = "文件大小（字节）", example = "102400")
    private long size;

    @Schema(description = "MIME 类型", example = "image/png")
    private String mime;

    @Schema(description = "容器内路径（沙箱 bind mount 后可见）", example = "/workspace/uploads/xxx-photo.png")
    private String containerPath;

    public AttachmentDto() {}

    public AttachmentDto(String id, String name, String path, long size, String mime) {
        this.id = id;
        this.name = name;
        this.path = path;
        this.size = size;
        this.mime = mime;
    }

    public AttachmentDto(String id, String name, String path, long size, String mime, String containerPath) {
        this.id = id;
        this.name = name;
        this.path = path;
        this.size = size;
        this.mime = mime;
        this.containerPath = containerPath;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getMime() { return mime; }
    public void setMime(String mime) { this.mime = mime; }

    public String getContainerPath() { return containerPath; }
    public void setContainerPath(String containerPath) { this.containerPath = containerPath; }
}
