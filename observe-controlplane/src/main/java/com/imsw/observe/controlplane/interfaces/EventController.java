package com.imsw.observe.controlplane.interfaces;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.pipeline.infrastructure.source.ApiSource;

/**
 * 外部 HTTP 事件入口（ADR-0007 addendum）。
 *
 * <p>路由：{@code POST /api/v1/events/api/{namespace}/{name}}。订阅以业务键 {@code (namespace, name)} 寻址
 * （与其它 controller 一致，ADR-0002 软隔离铁律）。ApiSource 内部按 (ns,name) 在 registry snapshot
 * 查订阅 → 命中则 wrap ApiEvent 入队 → 202 Accepted。找不到订阅（含 INACTIVE）→ IllegalArgumentException
 * 由 GlobalExceptionHandler 转译 404。
 *
 * <p>异步：HTTP 入口只入队即返回，pipeline 后台执行（与 CDC/Cron 入队语义一致）。Body = 任意业务 payload。
 */
@RestController
@RequestMapping("/api/v1/events/api")
public class EventController {

    private final ApiSource apiSource;

    public EventController(final ApiSource apiSource) {
        this.apiSource = apiSource;
    }

    @PostMapping("/{namespace}/{name}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<SubmitEventResponse> submit(
            @PathVariable final String namespace,
            @PathVariable final String name,
            @Valid @RequestBody final SubmitEventRequest req) {
        String eventId = UUID.randomUUID().toString();
        // ApiSource.dispatch 按 (ns,name) 查订阅 + wrap ApiEvent（subscriptionId 由其内部填）+ 入队。
        apiSource.dispatch(namespace, name, req.payload());
        return ApiResponse.ok(new SubmitEventResponse(eventId));
    }

    public record SubmitEventRequest(Map<String, Object> payload) {}

    public record SubmitEventResponse(String eventId) {}
}
