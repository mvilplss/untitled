package org.example.skill;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/skills")
@Tag(name = "Skill 管理", description = "Skill 仓库的上传、列表、查看与删除。Skill 是 .agentscope/skills/ 下的一个文件夹，name 取自 SKILL.md frontmatter，全局唯一。")
public class SkillController {

    private final SkillService service;

    public SkillController(SkillService service) {
        this.service = service;
    }

    @Operation(summary = "列出所有 Skill", description = "按 name 升序返回。返回的 SkillInfo 不含正文 content 字段。")
    @ApiResponse(responseCode = "200", description = "OK",
            content = @Content(schema = @Schema(implementation = SkillInfo.class)))
    @GetMapping
    public List<SkillInfo> list() {
        return service.list();
    }

    @Operation(summary = "按 name 查询 Skill", description = "返回详情，包含 SKILL.md 正文 content。")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = SkillInfo.class))),
            @ApiResponse(responseCode = "404", description = "Skill 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @GetMapping("/{name}")
    public SkillInfo get(@Parameter(description = "Skill name", example = "java-coding")
                         @PathVariable String name) {
        try {
            return service.get(name);
        } catch (NoSuchElementException e) {
            throw new org.example.web.NotFoundException(e.getMessage());
        }
    }

    @Operation(
            summary = "上传 Skill 包（zip/.skill）",
            description = "上传 multipart 文件。包内必须含 SKILL.md（YAML frontmatter 含 name/description），其它资源文件会被原样保留。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "创建成功",
                    content = @Content(schema = @Schema(implementation = SkillInfo.class))),
            @ApiResponse(responseCode = "400", description = "参数错误或 SKILL.md 解析失败",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SkillInfo> uploadZip(
            @Parameter(description = ".zip 或 .skill 文件，必须包含 SKILL.md", required = true)
            @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        SkillInfo created = service.createFromZip(file.getBytes());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(
            summary = "直接提交 SKILL.md 文本创建 Skill",
            description = "适用纯文本 skill（无资源文件场景）。name/description 来自 frontmatter。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "创建成功",
                    content = @Content(schema = @Schema(implementation = SkillInfo.class))),
            @ApiResponse(responseCode = "400", description = "内容为空或 frontmatter 缺失",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SkillInfo> uploadMarkdown(@RequestBody Map<String, String> body) {
        String markdown = body == null ? null : body.get("markdown");
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("body.markdown 不能为空");
        }
        SkillInfo created = service.createFromMarkdown(markdown);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(
            summary = "上传 Skill 文件夹（来自 <input webkitdirectory>）",
            description = "接收多个 multipart file，每个文件的 getOriginalFilename 携带 webkitRelativePath（形如 MySkill/SKILL.md），服务端按 / 切分还原目录树。"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "创建成功",
                    content = @Content(schema = @Schema(implementation = SkillInfo.class))),
            @ApiResponse(responseCode = "400", description = "参数错误或 SKILL.md 解析失败",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @PostMapping(value = "/folder", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SkillInfo> uploadFolder(
            @Parameter(description = "文件夹内的所有文件（field name 必须为 file）", required = true)
            @RequestParam("file") MultipartFile[] files) {
        SkillInfo created = service.createFromFolder(files);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "按 name 删除 Skill", description = "删除 .agentscope/skills/<name>/ 整个目录。")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "404", description = "Skill 不存在",
                    content = @Content(schema = @Schema(ref = "ErrorResponse")))
    })
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@Parameter(description = "Skill name", example = "java-coding")
                                       @PathVariable String name) {
        service.delete(name);
        return ResponseEntity.noContent().build();
    }
}