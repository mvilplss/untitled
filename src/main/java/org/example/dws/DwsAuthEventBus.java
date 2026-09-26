package org.example.dws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * device-flow 进度事件总线：每个 userId 持有一组 SSE 订阅者（前端 EventSource）。
 * dws 后台 polling 线程检测到 phase 变化时往这里推事件。
 */
@Component
public class DwsAuthEventBus {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicReference<AuthPhase> lastPhase = new AtomicReference<>(AuthPhase.IDLE);
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthPhase currentPhase() { return lastPhase.get(); }

    /** 注册前端 SSE 订阅，初始时立刻推送当前 phase。 */
    public SseEmitter register(String userId) {
        SseEmitter emitter = new SseEmitter(900_000L); // 15 分钟
        emitter.onCompletion(() -> remove(emitter));
        emitter.onTimeout(() -> remove(emitter));
        emitter.onError(e -> remove(emitter));
        emitters.add(emitter);

        // 立即推送当前 phase
        try {
            send(emitter, "flow_state", mapper.createObjectNode()
                    .put("phase", lastPhase.get().name().toLowerCase())
                    .toString());
        } catch (IOException e) {
            remove(emitter);
        }
        return emitter;
    }

    public void remove(SseEmitter emitter) {
        emitters.remove(emitter);
    }

    public void publishPhase(AuthPhase phase, JsonNode payload) {
        lastPhase.set(phase);
        String data = payload == null ? "{}" : payload.toString();
        for (SseEmitter e : emitters) {
            try {
                send(e, "flow_state", data);
                if (phase == AuthPhase.APPROVED || phase == AuthPhase.EXPIRED
                        || phase == AuthPhase.REJECTED || phase == AuthPhase.FAILED) {
                    send(e, "done", "{\"phase\":\"" + phase.name().toLowerCase() + "\"}");
                    remove(e);
                    e.complete();
                }
            } catch (IOException ex) {
                remove(e);
            }
        }
    }

    private void send(SseEmitter emitter, String event, String data) throws IOException {
        emitter.send(SseEmitter.event()
                .id(String.valueOf(System.currentTimeMillis()))
                .name(event)
                .data(data, MediaType.APPLICATION_JSON));
    }

    public enum AuthPhase {
        IDLE,         // 未发起过 device-flow
        WAITING,      // 等待用户在浏览器完成授权
        APPROVED,     // 授权成功，profile 已写入
        EXPIRED,      // device-code 过期（10 分钟）
        REJECTED,     // 用户拒绝
        FAILED        // 其他失败（dws 调用异常等）
    }
}
