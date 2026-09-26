package org.example.service;

import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import org.example.util.MimeUtils;
import org.example.web.dto.AttachmentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 构造含附件的 UserMessage。
 * <ul>
 *   <li>图片（mime 匹配 {@code image/*}）→ {@link DataBlock} + {@code Base64Source}，
 *       LLM 直接看到图片（多模态）。</li>
 *   <li>其他文件 → 拼到文本里的路径引用提示，Agent 用 read 工具读（仅文本可读）。</li>
 * </ul>
 */
public final class ChatMessageBuilder {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageBuilder.class);

    private ChatMessageBuilder() {}

    /**
     * 构造 UserMessage。
     *
     * @param text        用户输入文本（可空）
     * @param attachments 附件列表（可空）
     * @return UserMessage，含 TextBlock（用户文本 + 路径引用提示）和图片 DataBlock
     */
    public static UserMessage build(String text, List<AttachmentDto> attachments) {
        List<ContentBlock> blocks = new ArrayList<>();

        // 1. 用户文本
        StringBuilder textSb = new StringBuilder();
        if (text != null && !text.isBlank()) {
            textSb.append(text);
        }

        // 2. 处理附件
        List<String> pathHints = new ArrayList<>();
        List<ContentBlock> imageBlocks = new ArrayList<>();
        if (attachments != null) {
            for (AttachmentDto a : attachments) {
                if (a == null || a.getPath() == null) continue;
                if (MimeUtils.isImage(a.getMime())) {
                    // 图片：base64 → DataBlock 多模态
                    try {
                        String b64 = fileToBase64(a.getPath());
                        DataBlock img = DataBlock.builder()
                                .source(Base64Source.builder()
                                        .mediaType(a.getMime())
                                        .data(b64)
                                        .build())
                                .name(a.getName())
                                .build();
                        imageBlocks.add(img);
                    } catch (IOException e) {
                        log.warn("Failed to read image attachment {}: {}", a.getName(), e.getMessage());
                        pathHints.add(pathHint(a));
                    }
                } else {
                    // 其他文件：路径引用提示
                    pathHints.add(pathHint(a));
                }
            }
        }

        // 3. 组装 TextBlock（用户文本 + 路径引用提示）
        if (!pathHints.isEmpty()) {
            if (textSb.length() > 0) textSb.append("\n\n");
            textSb.append(String.join("\n", pathHints));
        }
        if (textSb.length() > 0) {
            blocks.add(TextBlock.builder().text(textSb.toString()).build());
        }

        // 4. 图片 DataBlock 追加
        blocks.addAll(imageBlocks);

        return new UserMessage(blocks);
    }

    /** 生成路径引用提示文本，让 Agent 知道有附件可以用 read 工具读。 */
    private static String pathHint(AttachmentDto a) {
        String containerPath = a.getContainerPath() != null
                ? a.getContainerPath()
                : a.getPath();
        return String.format("[附件] %s (%s, %d bytes) → 沙箱路径: %s",
                a.getName(), a.getMime(), a.getSize(), containerPath);
    }

    /** 读文件 → base64 字符串。 */
    private static String fileToBase64(String path) throws IOException {
        Path p = Paths.get(path);
        byte[] bytes = Files.readAllBytes(p);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
