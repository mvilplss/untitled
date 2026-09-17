package org.example;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import reactor.core.publisher.Flux;

import java.nio.file.Paths;
import java.util.List;

public class FirstAgent {
    public static void main(String[] args) {
        Model model = OpenAIChatModel.builder().baseUrl("https://api.minimaxi.com/v1")
                .modelName("minimax-m3")
                .apiKey("sk-cp-5yF6yf_fxvxQqrvVrzwWxj-XASmQuQSm9wn84yo2XFtVo7zPqdo5hFZgECvtpPhDy7vwNTGCoR0KCTgBFYWI_Er_ddudQx092iKo8-_ovZ2zBeeKzxVVbQs")
                .build();
        HarnessAgent agent = HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                .workspace(Paths.get(".agentscope/workspace"))
                .model(model)
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("demo-session")
                .userId("alice")
                .build();

        // 第一轮：自我介绍 + 当天的事
        agent.call(new UserMessage("我叫天宇，今天准备一个关于 ReAct 的技术分享。"), ctx).block();

        // 第二轮：同 sessionId，自动恢复上一轮状态后回答
        agent.call(new UserMessage("我叫什么？我今天要干什么？"), ctx).block();

        agent.streamEvents(new UserMessage("帮我把今天的关键点列三条。"))
                .doOnNext(event -> {
                    if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                        // 模型返回的流式文本片段 —— 追加到界面或标准输出
                        System.out.print(((TextBlockDeltaEvent) event).getDelta());
                    } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                        // 智能体即将调用工具 —— 展示调用信息
                        System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolCallName());
                    }
                    // 其他事件：思考块、工具结果、回复结束等
                })
                .blockLast();
    }
}