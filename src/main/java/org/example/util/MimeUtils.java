package org.example.util;

import org.springframework.http.MediaType;

/**
 * MIME 类型推断工具。
 * <p>从 {@code SandboxFileController.guessMediaType} 提取，供沙箱文件浏览与聊天附件共用。
 */
public final class MimeUtils {

    private MimeUtils() {}

    /** 按文件扩展名推断 MIME 类型，未知扩展名返回 {@code application/octet-stream}。 */
    public static MediaType guessMediaType(String name) {
        if (name == null) return MediaType.APPLICATION_OCTET_STREAM;
        String lower = name.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (lower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        if (lower.endsWith(".svg")) return MediaType.parseMediaType("image/svg+xml");
        if (lower.endsWith(".ico")) return MediaType.parseMediaType("image/x-icon");
        if (lower.endsWith(".bmp")) return MediaType.parseMediaType("image/bmp");
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")) {
            return MediaType.TEXT_PLAIN;
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return MediaType.TEXT_HTML;
        if (lower.endsWith(".xml")) return MediaType.APPLICATION_XML;
        if (lower.endsWith(".csv")) return MediaType.parseMediaType("text/csv");
        if (lower.endsWith(".docx")) return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        if (lower.endsWith(".xlsx")) return MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** 判断 MIME 是否是图片类型。 */
    public static boolean isImage(String mime) {
        return mime != null && mime.startsWith("image/");
    }

    /** 清理文件名，去掉 CRLF 和引号（防 Content-Disposition 注入）。 */
    public static String sanitizeFilename(String name) {
        if (name == null || name.isEmpty()) return "file";
        return name.replaceAll("[\\r\\n\"\\\\/]", "_");
    }
}
