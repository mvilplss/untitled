package org.example.skill;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.util.SkillFileSystemHelper;
import io.agentscope.core.skill.util.SkillUtil;
import org.example.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final Path skillsDir;

    public SkillService(AgentProperties props) {
        this.skillsDir = Paths.get(props.getConfig().getSkillsDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            log.warn("Cannot create skills dir {}: {}", skillsDir, e.getMessage());
        }
        log.info("SkillService initialized, skillsDir={}", skillsDir);
    }

    public Path getSkillsDir() {
        return skillsDir;
    }

    public List<SkillInfo> list() {
        if (!Files.isDirectory(skillsDir)) {
            return Collections.emptyList();
        }
        List<SkillInfo> out = new ArrayList<>();
        try (Stream<Path> subdirs = Files.list(skillsDir)) {
            subdirs
                    .filter(Files::isDirectory)
                    .filter(d -> !d.getFileName().toString().startsWith("."))
                    .forEach(dir -> {
                        SkillInfo info = safeRead(dir, false);
                        if (info != null) out.add(info);
                    });
        } catch (IOException e) {
            log.warn("Failed to list skills: {}", e.getMessage());
            return Collections.emptyList();
        }
        out.sort((a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    public SkillInfo get(String name) {
        validateName(name);
        Path skillDir = resolveSkillDir(name);
        if (!Files.isDirectory(skillDir)) {
            throw new NoSuchElementException("skill '" + name + "' not found");
        }
        SkillInfo info = safeRead(skillDir, true);
        if (info == null) {
            throw new IllegalStateException("skill '" + name + "' is invalid (no SKILL.md or missing name/description)");
        }
        return info;
    }

    /**
     * 上传 zip 包。name 取自 zip 内 SKILL.md frontmatter；同名覆盖。
     * 先清洗 zip（移除 macOS Finder 自动附带的 __MACOSX/ 与 .DS_Store 噪音），
     * 再交给 AgentScope 框架解析。
     */
    public SkillInfo createFromZip(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new IllegalArgumentException("zip 内容为空");
        }
        byte[] cleaned = stripAppleDoubleEntries(zipBytes);
        AgentSkill skill;
        try {
            skill = SkillUtil.createFromZip(cleaned);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 zip: " + e.getMessage(), e);
        }
        validateName(skill.getName());

        boolean ok = SkillFileSystemHelper.saveSkills(skillsDir, List.of(skill), true);
        if (!ok) {
            throw new IllegalStateException("保存 skill '" + skill.getName() + "' 失败");
        }
        log.info("Uploaded skill (zip): name={}, description={}",
                skill.getName(), skill.getDescription());
        return get(skill.getName());
    }

    /**
     * 上传文件夹（来自浏览器 <input webkitdirectory>）。
     * 每个 MultipartFile.getOriginalFilename() 形如 "MySkill/SKILL.md" 或
     * "MySkill/scripts/run.py" —— 浏览器把 webkitRelativePath 写进 multipart 的 filename 头。
     * 服务端按 "/" 切分还原目录树，并复用 SkillFileSystemHelper 落盘。
     */
    public SkillInfo createFromFolder(MultipartFile[] files) {
        if (files == null || files.length == 0) {
            throw new IllegalArgumentException("未收到任何文件");
        }

        String rootDir = null;
        String skillMdContent = null;
        Map<String, byte[]> resourceBytes = new LinkedHashMap<>();

        for (MultipartFile f : files) {
            String raw = f.getOriginalFilename();
            if (raw == null || raw.isEmpty()) continue;
            String path = raw.replace('\\', '/');
            int slash = path.indexOf('/');
            if (slash <= 0) {
                throw new IllegalArgumentException("文件缺少根目录: " + raw);
            }
            String r = path.substring(0, slash);
            if (rootDir == null) {
                rootDir = r;
            } else if (!rootDir.equals(r)) {
                throw new IllegalArgumentException(
                        "所有文件必须共享同一根目录，发现: " + rootDir + " vs " + r);
            }

            String relPath = path.substring(slash + 1);
            byte[] bytes;
            try {
                bytes = f.getBytes();
            } catch (IOException e) {
                throw new RuntimeException("读取 multipart 失败: " + e.getMessage(), e);
            }

            if ("SKILL.md".equals(relPath)) {
                skillMdContent = new String(bytes, StandardCharsets.UTF_8);
            } else {
                resourceBytes.put(relPath, bytes);
            }
        }

        if (skillMdContent == null) {
            throw new IllegalArgumentException(
                    "找不到 SKILL.md（应在 " + rootDir + "/SKILL.md）");
        }

        // 字符串化资源：NUL 字节视为二进制 → base64 编码
        Map<String, String> stringResources = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : resourceBytes.entrySet()) {
            if (isBinary(e.getValue())) {
                stringResources.put(e.getKey(),
                        "base64:" + Base64.getEncoder().encodeToString(e.getValue()));
            } else {
                stringResources.put(e.getKey(),
                        new String(e.getValue(), StandardCharsets.UTF_8));
            }
        }

        AgentSkill skill;
        try {
            skill = SkillUtil.createFrom(skillMdContent, stringResources);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 SKILL.md: " + e.getMessage(), e);
        }
        validateName(skill.getName());

        boolean ok = SkillFileSystemHelper.saveSkills(skillsDir, List.of(skill), true);
        if (!ok) {
            throw new IllegalStateException("保存 skill '" + skill.getName() + "' 失败");
        }
        log.info("Uploaded skill (folder): rootDir={}, name={}, resources={}",
                rootDir, skill.getName(), resourceBytes.size());
        return get(skill.getName());
    }

    /** 简易二进制探测：前 8KB 内含 NUL 字节则视为二进制 */
    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    /**
     * 移除 macOS Finder 自动附带的 __MACOSX/、._AppleDouble 与 .DS_Store 噪音条目，
     * 让 AgentScope 框架的"单根目录"校验通过。
     * 真正的多 root zip（两个 skill 并列）仍会被框架拒绝 —— 这才是真错误，不掩盖。
     */
    private byte[] stripAppleDoubleEntries(byte[] src) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(src.length);
        int kept = 0, dropped = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(src));
             ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (name.contains("__MACOSX/") || name.contains("__MACOSX\\")) {
                    dropped++;
                    continue;
                }
                if (name.equals(".DS_Store") || name.endsWith("/.DS_Store")) {
                    dropped++;
                    continue;
                }
                boolean appleDouble = false;
                for (String seg : name.split("[/\\\\]")) {
                    if (seg.startsWith("._")) {
                        appleDouble = true;
                        break;
                    }
                }
                if (appleDouble) {
                    dropped++;
                    continue;
                }
                ZipEntry outEntry = new ZipEntry(name);
                zout.putNextEntry(outEntry);
                zout.write(zin.readAllBytes());
                zout.closeEntry();
                kept++;
            }
        } catch (IOException ex) {
            throw new RuntimeException("清洗 zip 失败: " + ex.getMessage(), ex);
        }
        if (kept == 0) {
            throw new IllegalArgumentException(
                    "无法解析 zip: 清洗后没有可用条目（可能全是 macOS 资源叉或元数据）");
        }
        if (dropped > 0) {
            log.debug("Stripped {} macOS-noise entries from uploaded zip", dropped);
        }
        return out.toByteArray();
    }

    /**
     * 直接以 SKILL.md 文本创建（不含资源）。name 取自 frontmatter；同名覆盖。
     */
    public SkillInfo createFromMarkdown(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("SKILL.md 内容为空");
        }
        AgentSkill skill;
        try {
            skill = SkillUtil.createFrom(markdown, null);
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析 SKILL.md: " + e.getMessage(), e);
        }
        validateName(skill.getName());

        boolean ok = SkillFileSystemHelper.saveSkills(skillsDir, List.of(skill), true);
        if (!ok) {
            throw new IllegalStateException("保存 skill '" + skill.getName() + "' 失败");
        }
        log.info("Uploaded skill (markdown): name={}, description={}",
                skill.getName(), skill.getDescription());
        return get(skill.getName());
    }

    public void delete(String name) {
        validateName(name);
        Path skillDir = resolveSkillDir(name);
        if (!Files.isDirectory(skillDir)) {
            throw new NoSuchElementException("skill '" + name + "' not found");
        }
        try {
            SkillFileSystemHelper.deleteDirectory(skillDir);
            log.info("Deleted skill: {}", name);
        } catch (IOException e) {
            throw new RuntimeException("删除 skill '" + name + "' 失败: " + e.getMessage(), e);
        }
    }

    /** 返回 skill 目录中除 SKILL.md 外的资源文件数（递归） */
    public int countResources(Path skillDir) {
        try (Stream<Path> stream = Files.walk(skillDir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("SKILL.md"))
                    .filter(p -> {
                        try { return !Files.isHidden(p); }
                        catch (IOException e) { return true; }
                    })
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private SkillInfo safeRead(Path skillDir, boolean includeContent) {
        Path skillFile = skillDir.resolve("SKILL.md");
        if (!Files.isRegularFile(skillFile)) return null;
        try {
            String md = Files.readString(skillFile, StandardCharsets.UTF_8);
            AgentSkill skill = SkillUtil.createFrom(md, null);
            String name = skill.getName();
            String description = skill.getDescription();

            BasicFileAttributes attr = Files.readAttributes(skillFile, BasicFileAttributes.class);
            long size = attr.size();
            long modified = attr.lastModifiedTime().toInstant().getEpochSecond();
            int resourceCount = countResources(skillDir);

            Map<String, Object> allMeta = skill.getMetadata();
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : allMeta.entrySet()) {
                String k = e.getKey();
                if ("name".equals(k) || "description".equals(k)) continue;
                extra.put(k, e.getValue());
            }

            String content = includeContent ? skill.getSkillContent() : null;
            return new SkillInfo(name, description, content, extra, size, modified, resourceCount);
        } catch (Exception e) {
            log.warn("Failed to read skill at {}: {}", skillDir, e.getMessage());
            return null;
        }
    }

    private Path resolveSkillDir(String name) {
        Path resolved = skillsDir.resolve(name).normalize();
        if (!resolved.startsWith(skillsDir)) {
            throw new IllegalArgumentException("非法的 skill name: 路径穿越");
        }
        return resolved;
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("skill name 不能为空");
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "skill name 必须匹配 ^[a-zA-Z0-9_-]{1,64}$，实际: '" + name + "'");
        }
    }
}