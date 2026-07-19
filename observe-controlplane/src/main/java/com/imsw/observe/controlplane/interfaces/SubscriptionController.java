package com.imsw.observe.controlplane.interfaces;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.config.application.SubscriptionCrudService;
import com.imsw.observe.config.domain.SubscriptionDefinition;
import com.imsw.observe.config.domain.SubscriptionDefinition.ActionType;
import com.imsw.observe.config.domain.SubscriptionDefinition.Status;
import com.imsw.observe.controlplane.interfaces.dto.SubscriptionDto;
import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;
import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.domain.subscription.Condition;

/**
 * Subscription 资源以业务键 {@code (namespace, name)} 寻址（ADR-0002 软隔离铁律）。
 *
 * <p>路径形态：{@code /api/v1/namespaces/{namespace}/subscriptions/...}。创建时 namespace 取自路径，
 * 透传到 {@link SubscriptionFields} 以便 {@code toDomain()} 构造完整的领域对象。BIGINT id 不对外暴露。
 */
@RestController
@RequestMapping("/api/v1")
public class SubscriptionController {

    private final SubscriptionCrudService service;

    public SubscriptionController(final SubscriptionCrudService service) {
        this.service = service;
    }

    @PostMapping("/namespaces/{namespace}/subscriptions")
    public ApiResponse<SubscriptionDto> create(
            @PathVariable final String namespace, @Valid @RequestBody final CreateSubscriptionRequest req) {
        return ApiResponse.ok(SubscriptionDto.from(service.create(req.toDomain(namespace))));
    }

    @GetMapping("/namespaces/{namespace}/subscriptions")
    public ApiResponse<List<SubscriptionDto>> list(@PathVariable final String namespace) {
        return ApiResponse.ok(
                service.findAll(namespace).stream().map(SubscriptionDto::from).toList());
    }

    @GetMapping("/namespaces/{namespace}/subscriptions/{name}")
    public ApiResponse<SubscriptionDto> get(@PathVariable final String namespace, @PathVariable final String name) {
        var found = service.find(namespace, name);
        if (found == null) {
            throw new ResourceNotFoundException("subscription " + namespace + "/" + name + " not found");
        }
        return ApiResponse.ok(SubscriptionDto.from(found));
    }

    @PutMapping("/namespaces/{namespace}/subscriptions/{name}")
    public ApiResponse<SubscriptionDto> update(
            @PathVariable final String namespace,
            @PathVariable final String name,
            @Valid @RequestBody final UpdateSubscriptionRequest req) {
        return ApiResponse.ok(SubscriptionDto.from(service.update(namespace, name, req.toDomain(namespace))));
    }

    @DeleteMapping("/namespaces/{namespace}/subscriptions/{name}")
    public ApiResponse<Void> delete(@PathVariable final String namespace, @PathVariable final String name) {
        service.delete(namespace, name);
        return ApiResponse.ok(null);
    }

    /** 停用订阅（status → INACTIVE，不删配置；下次热加载 ≤30s 生效，loader 不再收此订阅进 registry）。 */
    @PostMapping("/namespaces/{namespace}/subscriptions/{name}/deactivate")
    public ApiResponse<SubscriptionDto> deactivate(
            @PathVariable final String namespace, @PathVariable final String name) {
        return ApiResponse.ok(SubscriptionDto.from(service.deactivate(namespace, name)));
    }

    /** 启用订阅（status → ACTIVE；下次热加载 ≤30s 重新进 registry 生效）。 */
    @PostMapping("/namespaces/{namespace}/subscriptions/{name}/activate")
    public ApiResponse<SubscriptionDto> activate(
            @PathVariable final String namespace, @PathVariable final String name) {
        return ApiResponse.ok(SubscriptionDto.from(service.activate(namespace, name)));
    }

    /** 可编辑的订阅字段（create/update 共用）。 */
    public record SubscriptionFields(
            @NotNull java.util.List<Long> pipelineIds,
            String mq,
            String topic,
            String db,
            String table,
            Set<CdcOp> opTypes,
            SourceType sourceType,
            Condition fieldFilter,
            String actionType,
            Long scheduleDelayMs,
            String scheduleCorrelationKeyPath,
            String name,
            String description,
            String status,
            String cronExpression,
            String cronName,
            String concurrent) {

        SubscriptionDefinition toDomain(final String namespace) {
            Duration delay = scheduleDelayMs == null ? null : Duration.ofMillis(scheduleDelayMs);
            SubscriptionDefinition.ActionType action =
                    actionType == null ? ActionType.RUN : ActionType.valueOf(actionType);
            SubscriptionDefinition.Status stat = status == null ? Status.ACTIVE : Status.valueOf(status);
            SubscriptionDefinition.Concurrent conc =
                    concurrent == null ? null : SubscriptionDefinition.Concurrent.valueOf(concurrent);
            return new SubscriptionDefinition(
                    null,
                    namespace,
                    pipelineIds,
                    mq,
                    topic,
                    db,
                    table,
                    opTypes,
                    sourceType,
                    fieldFilter,
                    action,
                    delay,
                    scheduleCorrelationKeyPath,
                    name,
                    description,
                    stat,
                    null,
                    null,
                    null,
                    cronExpression,
                    cronName,
                    conc);
        }
    }

    public record CreateSubscriptionRequest(@Valid @NotNull SubscriptionFields subscription) {

        SubscriptionDefinition toDomain(final String namespace) {
            return subscription.toDomain(namespace);
        }
    }

    public record UpdateSubscriptionRequest(@Valid @NotNull SubscriptionFields subscription) {

        SubscriptionDefinition toDomain(final String namespace) {
            return subscription.toDomain(namespace);
        }
    }
}
