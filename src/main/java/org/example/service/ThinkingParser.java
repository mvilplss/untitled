package org.example.service;

/**
 * 流式响应中的推理块与正文分离器。
 * 状态机在 chunk 边界保持，能够正确处理 <think> 或 <reasoning> 标签跨 chunk 的情况。
 */
public class ThinkingParser {

    public enum Kind { MESSAGE, THINKING }

    public record Chunk(Kind kind, String content) {}

    private static final String OPEN_A = "<think>";
    private static final String CLOSE_A = "</think>";
    private static final String OPEN_B = "<reasoning>";
    private static final String CLOSE_B = "</reasoning>";
    private static final int MAX_TAG_LEN = OPEN_B.length();

    private final StringBuilder buffer = new StringBuilder();
    private boolean inThinking = false;

    /**
     * 输入一段 delta 文本，输出 0..N 个已可确认类型的 Chunk。
     * 未确定归属（不完整的标签前缀）会留在内部 buffer 等下一段。
     */
    public java.util.List<Chunk> feed(String delta) {
        if (delta == null || delta.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        buffer.append(delta);
        java.util.List<Chunk> out = new java.util.ArrayList<>();
        while (true) {
            if (inThinking) {
                int idx = findClose(buffer);
                if (idx < 0) return out;
                if (idx > 0) {
                    out.add(new Chunk(Kind.THINKING, buffer.substring(0, idx)));
                }
                buffer.delete(0, idx + CLOSE_A.length());
                inThinking = false;
            } else {
                int openIdx = findOpen(buffer);
                if (openIdx < 0) {
                    // 防御：保留最后 MAX_TAG_LEN 个字符，避免 <reasoning> 跨 chunk 边界被切碎
                    int safeEnd = buffer.length() - MAX_TAG_LEN;
                    if (safeEnd > 0) {
                        out.add(new Chunk(Kind.MESSAGE, buffer.substring(0, safeEnd)));
                        buffer.delete(0, safeEnd);
                    }
                    return out;
                }
                if (openIdx > 0) {
                    out.add(new Chunk(Kind.MESSAGE, buffer.substring(0, openIdx)));
                }
                int tagLen = (buffer.indexOf(OPEN_B, openIdx) == openIdx) ? OPEN_B.length() : OPEN_A.length();
                buffer.delete(0, openIdx + tagLen);
                inThinking = true;
            }
        }
    }

    /**
     * 流结束时把 buffer 残留冲掉。若仍处于 THINKING 状态也按 THINKING 输出。
     */
    public Chunk flush() {
        if (buffer.length() == 0) return null;
        Chunk c = new Chunk(inThinking ? Kind.THINKING : Kind.MESSAGE, buffer.toString());
        buffer.setLength(0);
        return c;
    }

    private static int findOpen(StringBuilder sb) {
        int a = sb.indexOf(OPEN_A);
        int b = sb.indexOf(OPEN_B);
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    private static int findClose(StringBuilder sb) {
        int a = sb.indexOf(CLOSE_A);
        int b = sb.indexOf(CLOSE_B);
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }
}