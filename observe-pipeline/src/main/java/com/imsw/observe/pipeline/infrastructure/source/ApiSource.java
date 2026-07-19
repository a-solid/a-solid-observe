package com.imsw.observe.pipeline.infrastructure.source;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.kernel.event.model.ApiEvent;
import com.imsw.observe.kernel.event.model.ApiMeta;
import com.imsw.observe.pipeline.application.EventListener;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.application.Source;
import com.imsw.observe.pipeline.domain.subscription.Subscription;

/**
 * HTTP 触发的 API 事件源。对外通过 {@code POST /api/v1/events/api/{namespace}/{name}} 入口接收调用方请求，
 * 按 (namespace, name) 在 registry snapshot 查订阅得 subscriptionId，wrap 成 {@link ApiEvent} 入 dispatcher 队列。
 *
 * <p>路由契约（ADR-0007 addendum）：
 * <ul>
 *   <li>找不到订阅（(ns,name) 不存在）→ {@link IllegalArgumentException}，controller 转译为 404。</li>
 *   <li>订阅 INACTIVE → 同样 {@link IllegalArgumentException}（对外契约：与"不存在"等价）。</li>
 *   <li>命中 → 异步入队（{@link EventListener#onEvent}），HTTP 入口立即返回 202 Accepted。</li>
 * </ul>
 *
 * <p>本类属 infrastructure 层：依赖 application 端口 {@link PipelineRegistry}（通过 {@link Supplier} 延迟解析，
 * 与 dispatcher::onEvent 注入风格一致）+ kernel Event。registry 是 application 层端口，符合"infrastructure
 * 适配 application 端口"的依赖方向。
 */
public final class ApiSource implements Source {

    private static final Logger LOG = LoggerFactory.getLogger(ApiSource.class);

    private final Supplier<PipelineRegistry> registrySupplier;

    private EventListener listener;

    public ApiSource(final Supplier<PipelineRegistry> registrySupplier) {
        this.registrySupplier = registrySupplier;
    }

    @Override
    public void start(final EventListener listener) {
        this.listener = listener;
        LOG.info("ApiSource started");
    }

    @Override
    public void stop() {
        listener = null;
        LOG.info("ApiSource stopped");
    }

    /**
     * HTTP 入口：按 (namespace, name) 查订阅，wrap ApiEvent 入队。
     *
     * @throws IllegalStateException  ApiSource 未 start（listener 为 null）
     * @throws IllegalArgumentException (namespace, name) 找不到订阅或订阅 INACTIVE
     */
    public void dispatch(final String namespace, final String name, final Map<String, Object> payload) {
        if (listener == null) {
            throw new IllegalStateException("ApiSource not started");
        }
        PipelineRegistry registry = registrySupplier.get();
        if (registry == null) {
            throw new IllegalStateException("PipelineRegistry not available");
        }
        PipelineRegistry.Snapshot snapshot = registry.snapshot();
        Subscription sub = snapshot.subscriptionByNamespaceAndName(namespace, name);
        if (sub == null) {
            // INACTIVE 订阅 loader 已过滤（不进 snapshot），故 (ns,name) 找不到覆盖"不存在"和"INACTIVE"两种——
            // 对调用方一视同仁返回 404（详见 ADR-0007 addendum）。
            throw new IllegalArgumentException("subscription not found or inactive: " + namespace + "/" + name);
        }
        Map<String, Object> body = payload == null ? Map.of() : Map.copyOf(payload);
        Map<String, Object> attributes = Map.of(
                "http_method",
                "POST",
                "http_path",
                "/api/v1/events/api/" + namespace + "/" + name,
                "received_at",
                Instant.now().toString());
        ApiMeta meta = new ApiMeta(sub.id(), attributes);
        ApiEvent event = new ApiEvent(meta, body, Instant.now());
        listener.onEvent(event);
    }
}
