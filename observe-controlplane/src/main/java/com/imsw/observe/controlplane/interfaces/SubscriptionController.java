package com.imsw.observe.controlplane.interfaces;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.http.ResponseEntity;
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
import com.imsw.observe.kernel.event.model.Op;
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
    public SubscriptionDto create(
            @PathVariable final String namespace, @RequestBody final CreateSubscriptionRequest req) {
        return SubscriptionDto.from(service.create(req.toDomain(namespace)));
    }

    @GetMapping("/namespaces/{namespace}/subscriptions")
    public List<SubscriptionDto> list(@PathVariable final String namespace) {
        return service.findAll(namespace).stream().map(SubscriptionDto::from).toList();
    }

    @GetMapping("/namespaces/{namespace}/subscriptions/{name}")
    public ResponseEntity<SubscriptionDto> get(@PathVariable final String namespace, @PathVariable final String name) {
        var found = service.find(namespace, name);
        return found == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(SubscriptionDto.from(found));
    }

    @PutMapping("/namespaces/{namespace}/subscriptions/{name}")
    public SubscriptionDto update(
            @PathVariable final String namespace,
            @PathVariable final String name,
            @RequestBody final UpdateSubscriptionRequest req) {
        return SubscriptionDto.from(service.update(namespace, name, req.toDomain(namespace)));
    }

    @DeleteMapping("/namespaces/{namespace}/subscriptions/{name}")
    public void delete(@PathVariable final String namespace, @PathVariable final String name) {
        service.delete(namespace, name);
    }

    /** 可编辑的订阅字段（create/update 共用）。 */
    public record SubscriptionFields(
            Long pipelineId,
            int pipelineVersion,
            String mq,
            String topic,
            String db,
            String table,
            Set<Op> opTypes,
            SourceType sourceType,
            Condition fieldFilter,
            String actionType,
            Long scheduleDelayMs,
            String scheduleCorrelationKeyPath,
            String name,
            String description,
            String status) {

        SubscriptionDefinition toDomain(final String namespace) {
            Duration delay = scheduleDelayMs == null ? null : Duration.ofMillis(scheduleDelayMs);
            SubscriptionDefinition.ActionType action =
                    actionType == null ? ActionType.RUN : ActionType.valueOf(actionType);
            SubscriptionDefinition.Status stat = status == null ? Status.ACTIVE : Status.valueOf(status);
            return new SubscriptionDefinition(
                    null,
                    namespace,
                    pipelineId,
                    pipelineVersion,
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
                    null);
        }
    }

    public record CreateSubscriptionRequest(SubscriptionFields subscription) {

        SubscriptionDefinition toDomain(final String namespace) {
            return subscription.toDomain(namespace);
        }
    }

    public record UpdateSubscriptionRequest(SubscriptionFields subscription) {

        SubscriptionDefinition toDomain(final String namespace) {
            return subscription.toDomain(namespace);
        }
    }
}
