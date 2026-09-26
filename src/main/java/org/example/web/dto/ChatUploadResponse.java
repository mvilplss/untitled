package org.example.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;

@Schema(description = "聊天附件上传响应")
public class ChatUploadResponse {

    @Schema(description = "已上传的附件列表")
    private List<AttachmentDto> attachments = new ArrayList<>();

    public ChatUploadResponse() {}

    public ChatUploadResponse(List<AttachmentDto> attachments) {
        this.attachments = attachments == null ? new ArrayList<>() : attachments;
    }

    public List<AttachmentDto> getAttachments() { return attachments; }
    public void setAttachments(List<AttachmentDto> attachments) {
        this.attachments = attachments == null ? new ArrayList<>() : attachments;
    }
}
