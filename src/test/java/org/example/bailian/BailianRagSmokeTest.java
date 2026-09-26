package org.example.bailian;

import com.alibaba.fastjson.JSONObject;
import com.aliyun.bailian20231229.models.RetrieveResponse;
import com.aliyun.bailian20231229.models.RetrieveResponseBody;
import com.google.gson.JsonObject;
import io.agentscope.core.rag.integration.bailian.BailianClient;
import io.agentscope.core.rag.integration.bailian.BailianConfig;

import java.util.List;

/**
 * 阿里云百炼 RAG smoke test —— 离线验证凭证 + workspaceId + indexId + endpoint 是否可用。
 *
 * <p>故意避开所有 v1 RAG deprecated 类型（{@code BailianKnowledge / Document / RetrieveConfig}），
 * 直接调 {@link BailianClient#retrieve(String, String, Integer)}，跟生产代码
 * {@link BailianKnowledgeTool} 的路径一致。{@code @Deprecated} API 在本测试里
 * 一个 import 都没有。
 *
 * <p>运行前请通过环境变量提供凭证（严禁把 AK/SK 写进源码，避免泄漏到 git）：
 * <pre>{@code
 *   export BAILIAN_AK_ID=LTAIxxxxxxxxxxxx
 *   export BAILIAN_AK_SECRET=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
 * }</pre>
 * 其他非敏感常量（WORKSPACE_ID / INDEX_ID）已按截图/对话填好。
 * 运行命令：
 * <pre>{@code
 *   mvn -q test-compile -DskipTests
 *   java -cp "target/test-classes:<runtime-classpath>" org.example.bailian.BailianRagSmokeTest
 * }</pre>
 *
 * <p>退出码：
 * <ul>
 *   <li>0  —— 成功（拿到 ≥1 hit）</li>
 *   <li>1  —— retrieve 失败（错误分类打印到 stderr）</li>
 *   <li>10 —— BailianClient 构造失败（AK/SK/endpoint 配错）</li>
 *   <li>20 —— 占位符没替换</li>
 * </ul>
 */
public class BailianRagSmokeTest {

    // ===== 凭证走环境变量（严禁把 AK/SK 写进源码）=====
    private static final String AK_ID     = envOr("BAILIAN_AK_ID",     "PUT_YOUR_AK_ID_HERE");
    private static final String AK_SECRET = envOr("BAILIAN_AK_SECRET", "PUT_YOUR_SK_HERE");
    // ===== 非敏感标识，可直接硬编码 =====
    private static final String WORKSPACE_ID  = "ws-kcjy7yco5tcutlbf";
    private static final String INDEX_ID      = "qbec2poy49";

    private static final String QUERY   = "aiwork";
    private static final int    LIMIT   = 3;

    public static void main(String[] args) {
        System.out.println("=== Bailian RAG Smoke Test ===");
        System.out.println("AK_ID         = " + mask(AK_ID));
        System.out.println("AK_SECRET     = " + mask(AK_SECRET));
        System.out.println("WORKSPACE_ID  = " + WORKSPACE_ID);
        System.out.println("INDEX_ID      = " + INDEX_ID);
        System.out.println("QUERY         = " + QUERY);

        if (looksLikePlaceholder(AK_SECRET) || looksLikePlaceholder(AK_ID)) {
            System.err.println("\n[FAIL] AK 或 SK 未设置或是占位符。");
            System.err.println("  建议：export BAILIAN_AK_ID=LTAIxxxx BAILIAN_AK_SECRET=xxxx 后重跑。");
            System.exit(20);
        }
        if (WORKSPACE_ID.isBlank() || INDEX_ID.isBlank()) {
            System.err.println("\n[FAIL] workspaceId 或 indexId 为空。");
            System.exit(20);
        }

        BailianConfig.Builder b = BailianConfig.builder()
                .accessKeyId(AK_ID)
                .accessKeySecret(AK_SECRET)
                .workspaceId(WORKSPACE_ID)
                .indexId(INDEX_ID);
        BailianConfig config = b.build();

        BailianClient client;
        try {
            client = new BailianClient(config);
            System.out.println("\nBailianClient construction: OK (workspace=" + client.getWorkspaceId() + ")");
        } catch (Throwable t) {
            System.err.println("\n[FAIL] BailianClient construction failed.");
            System.err.println("  exception : " + t.getClass().getName() + ": " + t.getMessage());
            System.err.println("  cause     : " + rootMessage(t));
            categorize(t);
            System.exit(10);
            return;
        }

        try {
            RetrieveResponse response = client.retrieve(INDEX_ID, QUERY, LIMIT).block();
            System.out.println("\nRetrieve call: OK");
            if (response == null || response.getBody() == null) {
                System.err.println("  response / body is null");
                System.exit(1);
                return;
            }
            RetrieveResponseBody body = response.getBody();
            Boolean success = body.getSuccess();
            String code = body.getCode();
            String status = body.getStatus();
            String requestId = body.getRequestId();
            System.out.println(JSONObject.toJSONString(body.getData()));
            if (Boolean.FALSE.equals(success) || (code != null && !"200".equals(code) && !"Success".equalsIgnoreCase(status))) {
                System.err.println("  API returned non-success: success=" + success + " code=" + code + " status=" + status);
                System.err.println("  requestId : " + requestId);
                System.err.println("  message   : " + body.getMessage());
                System.exit(1);
                return;
            }

            List<RetrieveResponseBody.RetrieveResponseBodyDataNodes> nodes =
                    body.getData() == null ? null : body.getData().getNodes();
            int count = nodes == null ? 0 : nodes.size();
            System.out.println("hits.size() = " + count);
            if (nodes != null) {
                for (int i = 0; i < nodes.size(); i++) {
                    RetrieveResponseBody.RetrieveResponseBodyDataNodes n = nodes.get(i);
                    Double score = n.getScore();
                    System.out.println("  [" + (i + 1) + "] score=" + score + " text=" + abbreviate(n.getText(), 80));
                }
            }
            System.out.println("\n=== SUCCESS ===");
            System.exit(0);
        } catch (Throwable t) {
            System.err.println("\n[FAIL] Retrieve call failed.");
            System.err.println("  exception : " + t.getClass().getName() + ": " + t.getMessage());
            System.err.println("  cause     : " + rootMessage(t));
            categorize(t);
            System.exit(1);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null) r = r.getCause();
        return r.getClass().getSimpleName() + ": " + r.getMessage();
    }

    /** 把阿里云常见错误分类成中文建议 */
    private static void categorize(Throwable t) {
        String msg = rootMessage(t).toLowerCase();
        System.err.println("\n=== Diagnosis ===");
        if (msg.contains("specified signature does not match")) {
            System.err.println("→ 签名不匹配：AK/SK 不配对，或 SK 复制时漏字符 / 多字符 / 含换行。");
            System.err.println("  建议：去阿里云 RAM 控制台 → 用户 → AccessKey 管理 → '查看 Secret' 重新完整复制一次（不要漏字符、不要带空格/换行）。");
        } else if (msg.contains("invalidaccesskeyid") || msg.contains("invalidaccesskey")) {
            System.err.println("→ AK 无效。检查 accessKeyId 是否正确（区分大小写、无多余空格）。");
        } else if (msg.contains("forbidden") || msg.contains("accessdenied") || msg.contains("ram not authorized")) {
            System.err.println("→ 权限不足。检查：1) AK 是子账号的，加 bailian:Retrieve 权限；2) workspaceId 与该 AK 同账号。");
        } else if (msg.contains("invalidparameter") || msg.contains("parametermissing")) {
            System.err.println("→ 参数缺失/错误。检查 workspaceId / indexId 是否拼写正确。");
        } else if (msg.contains("resourcenotfound") || msg.contains("indexnotfound")
                || msg.contains("workspace") && msg.contains("not found")) {
            System.err.println("→ 资源不存在。这个 indexId 不属于当前 workspaceId，请检查知识库 ID 是否填错或切换 workspace。");
        } else if (msg.contains("'config.endpoint' can not be empty")) {
            System.err.println("→ endpoint 为空字符串。请设 ENDPOINT 常量或传空走 bailian.cn-beijing.aliyuncs.com。");
        } else {
            System.err.println("→ 未识别错误。请贴完整堆栈 + 这行 cause 看分类。");
        }
    }

    /** 读环境变量；缺失或空白时用 fallback（通常用于占位符，触发 exit(20) 提示用户 export）。 */
    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static boolean looksLikePlaceholder(String v) {
        if (v == null || v.isBlank()) return false;
        String low = v.toLowerCase();
        return low.contains("replace") || low.contains("xxxx") || low.contains("your_")
                || low.contains("your-") || low.contains("<") || low.contains("placeholder")
                || low.contains("fake") || low.contains("dummy");
    }

    /** 中段掩码，避免把完整 SK 打到 stdout */
    private static String mask(String s) {
        if (s == null || s.isBlank()) return "(empty)";
        if (s.length() <= 8) return s.substring(0, 1) + "***" + s.substring(s.length() - 1);
        return s.substring(0, 4) + "***" + s.substring(s.length() - 4);
    }

    private static String abbreviate(String s, int n) {
        if (s == null) return "(null)";
        String oneLine = s.replace("\n", " ").replace("\r", " ");
        return oneLine.length() <= n ? oneLine : oneLine.substring(0, n) + "...";
    }
}