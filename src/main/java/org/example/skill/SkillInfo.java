package org.example.skill;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Skill 元数据。Skill 是存放在 .agentscope/skills/ 下的一个文件夹，包含 SKILL.md（YAML frontmatter + 正文）以及可选的资源文件。")
public class SkillInfo {

    @Schema(description = "Skill 唯一标识（取自 SKILL.md frontmatter name），全字母数字下划线连字符",
            example = "java-coding",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "Skill 简短说明（取自 SKILL.md frontmatter description）",
            example = "资深 Java 编程助手",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String description;

    @Schema(description = "SKILL.md 正文（不含 YAML frontmatter）。仅详情接口返回。")
    private String content;

    @Schema(description = "frontmatter 中除 name/description 之外的其他字段（version/tags/author 等），用于扩展点")
    private Map<String, Object> extraMetadata;

    @Schema(description = "SKILL.md 文件大小（字节）", example = "1024")
    private long sizeBytes;

    @Schema(description = "最后修改时间（秒）", example = "1789522608")
    private long modifiedAt;

    @Schema(description = "资源文件数（不含 SKILL.md）", example = "2")
    private int resourceCount;

    public SkillInfo() {}

    public SkillInfo(String name, String description, String content,
                     Map<String, Object> extraMetadata,
                     long sizeBytes, long modifiedAt, int resourceCount) {
        this.name = name;
        this.description = description;
        this.content = content;
        this.extraMetadata = extraMetadata;
        this.sizeBytes = sizeBytes;
        this.modifiedAt = modifiedAt;
        this.resourceCount = resourceCount;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Map<String, Object> getExtraMetadata() { return extraMetadata; }
    public void setExtraMetadata(Map<String, Object> extraMetadata) { this.extraMetadata = extraMetadata; }

    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }

    public long getModifiedAt() { return modifiedAt; }
    public void setModifiedAt(long modifiedAt) { this.modifiedAt = modifiedAt; }

    public int getResourceCount() { return resourceCount; }
    public void setResourceCount(int resourceCount) { this.resourceCount = resourceCount; }
}