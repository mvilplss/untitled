package org.example.config;

import io.netty.handler.flush.FlushConsolidationHandler;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * 禁用 Netty FlushConsolidationHandler 对多次 flush 的合并。
 * <p>WebFlux 的 {@code writeAndFlushWith} 会按 SSE event 触发 flush，
 * 但 Netty 的 {@link FlushConsolidationHandler} 会在同一 event loop tick 内
 * 合并多次 flush，导致浏览器端看到批量 SSE 而非实时。
 * <p>显式设置 {@code explicitFlushAfterFlushes=1} + {@code consolidateWhenNoReadInProgress=false}
 * 确保每次 flush 立即推进到 socket。
 */
@Component
public class SseNettyCustomizer implements WebServerFactoryCustomizer<NettyReactiveWebServerFactory> {

    @Override
    public void customize(NettyReactiveWebServerFactory factory) {
        factory.addServerCustomizers(httpServer ->
                httpServer.doOnConnection(conn ->
                        conn.channel().pipeline().addBefore(
                                "HttpServerCodec",
                                "sseFlushHandler",
                                new FlushConsolidationHandler(1, false))));
    }
}
