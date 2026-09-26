package org.example.bailian;

import io.agentscope.core.memory.bailian.BailianLongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;

import java.util.List;

/**
 * 阿里云百炼长期记忆 smoke test —— 离线验证 DASHSCOPE_API_KEY + memoryLibraryId
 * （+ 可选 projectId / profileSchema）是否可用，验证 record / retrieve 双向链路通。
 *
 * <p>用户维度按本次规划固定为 {@code default-user}（先跑通再说）。
 * 检索增强开关默认全关（rerank / judge / rewrite），便于观察基线延迟；想打开
 * 任一项取消对应 {@code .enableXxx(true)} 注释即可。
 *
 * <h2>⚠️ SDK 已知陷阱</h2>
 * {@link io.agentscope.core.model.transport.HttpTransportFactory#getDefault()} 返回进程级
 * <b>单例 transport</b>。{@code BailianLongTermMemory.Builder} 默认就用这个单例构造
 * {@code BailianMemoryClient}。所以 <b>绝对不要中途关闭</b> {@code BailianLongTermMemory}——
 * 一旦 close()，进程内其他所有走这个 singleton transport 的 HTTP 调用都会
 * {@code HttpTransportException("Transport has been closed")}。
 *
 * <p>结论：本测试 <b>不调用 close()</b>，由 JVM shutdown hook 兜底关闭。
 * 生产代码中 {@code BailianLongTermMemory} 应保持单例长生命周期，跟随 Spring 容器。
 *
 * <p>运行命令：
 * <pre>{@code
 *   export DASHSCOPE_API_KEY=sk-xxxxx
 *   # 编辑源码把 MEMORY_LIBRARY_ID 改成真值（百炼控制台 → 记忆库 → 复制 ID）
 *   mvn -q test-compile
 *   java -cp "target/test-classes:$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null)" \
 *        org.example.bailian.BailianMemorySmokeTest
 * }</pre>
 *
 * <p>退出码：
 * <ul>
 *   <li>0  —— 成功（record 成功 + 拿到 ≥0 条 retrieve 结果）</li>
 *   <li>1  —— record / retrieve 调用失败</li>
 *   <li>10 —— BailianLongTermMemory 构造失败（apiKey / userId 缺失）</li>
 *   <li>20 —— 占位符没替换 / 环境变量缺失</li>
 * </ul>
 */
public class BailianMemorySmokeTest {

    // ===== 在这里改标识 ===== ↓↓↓
    private static final String MEMORY_LIBRARY_ID = "f8b75d6af3694c97bb0ccd980bd92078";
    private static final String PROJECT_ID        = "PROJECT_ID_1";   // 可选：留空跳过
    private static final String PROFILE_SCHEMA    = "";   // 可选：留空跳过
    // ===== 在这里改标识 ===== ↑↑↑

    private static final String USER_ID = "default-user";

    private static final String SAY     = "帮我每天 9 点提醒喝水";
    private static final String RECALL  = "我有什么提醒？";

    public static void main(String[] args) {
        System.out.println("=== Bailian Memory Smoke Test ===");

        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("\n[FAIL] DASHSCOPE_API_KEY 环境变量未设置。");
            System.err.println("  建议：export DASHSCOPE_API_KEY=sk-xxxxx 后重跑。");
            System.exit(20);
            return;
        }
        if (MEMORY_LIBRARY_ID.contains("PUT_YOUR") || looksLikePlaceholder(MEMORY_LIBRARY_ID)) {
            System.err.println("\n[FAIL] MEMORY_LIBRARY_ID 还是占位符。");
            System.err.println("  建议：去阿里云百炼控制台 → 记忆库 → 复制 memoryLibraryId。");
            System.exit(20);
            return;
        }

        System.out.println("API_KEY          = " + mask(apiKey));
        System.out.println("USER_ID          = " + USER_ID);
        System.out.println("MEMORY_LIBRARY_ID= " + MEMORY_LIBRARY_ID);
        System.out.println("PROJECT_ID       = " + (PROJECT_ID.isBlank() ? "(skipped)" : PROJECT_ID));
        System.out.println("PROFILE_SCHEMA   = " + (PROFILE_SCHEMA.isBlank() ? "(skipped)" : PROFILE_SCHEMA));

        BailianLongTermMemory.Builder builder = BailianLongTermMemory.builder()
                .apiKey(apiKey)
                .userId(USER_ID)
                .memoryLibraryId(MEMORY_LIBRARY_ID)
                .topK(10)
                .minScore(0.3);
        if (!PROJECT_ID.isBlank())     builder.projectId(PROJECT_ID);
        if (!PROFILE_SCHEMA.isBlank()) builder.profileSchema(PROFILE_SCHEMA);
        // 想启用可解开下面三行（默认全关，观察基线）
        // .enableRerank(true)
        // .enableJudge(true)
        // .enableRewrite(true)

        // ⚠️ 关键：单例 long-term，BailianLongTermMemory 整个生命周期只 build 一次。
        //    不要再起第二个 try-with-resources 块，也不要在中途 close()——
        //    HttpTransportFactory.getDefault() 是进程级单例，关掉它会炸掉
        //    进程内所有其他 HTTP 客户端。
        BailianLongTermMemory memory;
        try {
            memory = builder.build();
            System.out.println("\nBailianLongTermMemory construction: OK");
        } catch (Throwable t) {
            System.err.println("\n[FAIL] BailianLongTermMemory construction failed.");
            System.err.println("  exception : " + t.getClass().getName() + ": " + t.getMessage());
            System.exit(10);
            return;
        }

        try {
            Msg userMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder().text(SAY).build())
                    .build();

            System.out.println("\n[record] sending: " + SAY);
            memory.record(List.of(userMsg)).block();
            System.out.println("[record] OK");

            Msg recallMsg = Msg.builder()
                    .role(MsgRole.USER)
                    .content(TextBlock.builder().text(RECALL).build())
                    .build();

            System.out.println("\n[retrieve] querying: " + RECALL);
            String recalled = memory.retrieve(recallMsg).block();
            System.out.println("[retrieve] OK");

            if (recalled == null) {
                System.err.println("  recalled text is null");
                System.exit(1);
                return;
            }
            if (recalled.isBlank()) {
                System.out.println("  recalled text is empty (memory 还没落库或语义不命中，正常)");
                System.out.println("\n=== SUCCESS (no hit) ===");
                System.exit(0);
                return;
            }

            System.out.println("\n--- recalled memory ---");
            System.out.println(recalled);
            System.out.println("--- end ---");
            System.out.println("\n=== SUCCESS ===");
            System.exit(0);

        } catch (Throwable t) {
            System.err.println("\n[FAIL] record / retrieve failed.");
            System.err.println("  exception : " + t.getClass().getName() + ": " + t.getMessage());
            System.err.println("  cause     : " + rootMessage(t));
            categorize(t);
            System.exit(1);
        }
        // 不调 memory.close()：见 javadoc 中 SDK 陷阱说明。
        // JVM shutdown hook 会兜底关闭 singleton transport。
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) r = r.getCause();
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }

    /** 把阿里云 dashscope 常见错误分类成中文建议 */
    private static void categorize(Throwable t) {
        String msg = rootMessage(t).toLowerCase();
        System.err.println("\n=== Diagnosis ===");
        if (msg.contains("invalidapi") || msg.contains("invalid api key") || msg.contains("apikey")) {
            System.err.println("→ API Key 无效或已过期。");
            System.err.println("  建议：去阿里云百炼控制台 → API-Key 管理 → 重新生成或复制。");
        } else if (msg.contains("forbidden") || msg.contains("accessdenied") || msg.contains("ram not authorized")) {
            System.err.println("→ 权限不足。检查该 API Key 是否开通了「长期记忆」服务。");
        } else if (msg.contains("resourcenotfound") || msg.contains("memorylibrary") && msg.contains("not found")) {
            System.err.println("→ memoryLibraryId 不存在。检查百炼控制台 → 记忆库 → 复制 ID。");
        } else if (msg.contains("invalidparameter") || msg.contains("parametermissing")) {
            System.err.println("→ 参数缺失/错误。检查 memoryLibraryId / projectId 拼写。");
        } else if (msg.contains("transport has been closed")) {
            System.err.println("→ Transport 已被关闭（SDK 陷阱）。");
            System.err.println("  原因：BailianLongTermMemory.close() 会关闭 HttpTransportFactory.getDefault() 这个进程级单例。");
            System.err.println("  建议：不要中途 close()，让 JVM shutdown hook 兜底；或每个进程只持有一个 BailianLongTermMemory 实例。");
        } else if (msg.contains("quota") || msg.contains("throttle") || msg.contains("rate limit")) {
            System.err.println("→ 配额/限流。等几秒重试，或到控制台提配额。");
        } else {
            System.err.println("→ 未识别错误。请贴完整堆栈 + 这行 cause 看分类。");
        }
    }

    private static boolean looksLikePlaceholder(String v) {
        if (v == null || v.isBlank()) return false;
        String low = v.toLowerCase();
        return low.contains("replace") || low.contains("xxxx") || low.contains("your_")
                || low.contains("your-") || low.contains("<") || low.contains("placeholder")
                || low.contains("fake") || low.contains("dummy");
    }

    /** 中段掩码，避免把完整 API Key 打到 stdout */
    private static String mask(String s) {
        if (s == null || s.isBlank()) return "(empty)";
        if (s.length() <= 8) return s.substring(0, 1) + "***" + s.substring(s.length() - 1);
        return s.substring(0, 4) + "***" + s.substring(s.length() - 4);
    }
}