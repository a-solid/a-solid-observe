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
import com.imsw.observe.config.domain.Subscription;
import com.imsw.observe.controlplane.interfaces.dto.SubscriptionDto;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.domain.subscription.Condition;

@RestController
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {

    private final SubscriptionCrudService service;

    public SubscriptionController(final SubscriptionCrudService service) {
        this.service = service;
    }

    @PostMapping
    public SubscriptionDto create(@RequestBody final CreateSubscriptionRequest req) {
        return SubscriptionDto.from(service.create(req.toDomain(null)));
    }

    @GetMapping
    public List<SubscriptionDto> list() {
        return service.findAll().stream().map(SubscriptionDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SubscriptionDto> get(@PathVariable final String id) {
        var found = service.find(id);
        return found == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(SubscriptionDto.from(found));
    }

    @PutMapping("/{id}")
    public SubscriptionDto update(@PathVariable final String id, @RequestBody final UpdateSubscriptionRequest req) {
        return SubscriptionDto.from(service.update(id, req.toDomain()));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable final String id) {
        service.delete(id);
    }

    /** 可编辑的订阅字段（create/update 共用）。 */
    public record SubscriptionFields(
            String pipelineId,
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
            String status) {

        Subscription toDomain(final String id) {
            Duration delay = scheduleDelayMs == null ? null : Duration.ofMillis(scheduleDelayMs);
            Subscription.ActionType action =
                    actionType == null ? Subscription.ActionType.RUN : Subscription.ActionType.valueOf(actionType);
            Subscription.Status stat =
                    status == null ? Subscription.Status.ACTIVE : Subscription.Status.valueOf(status);
            return new Subscription(
                    id,
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
                    null,
                    null,
                    stat,
                    null,
                    null,
                    null);
        }
    }

    public record CreateSubscriptionRequest(SubscriptionFields subscription) {

        Subscription toDomain(final String id) {
            return subscription.toDomain(id);
        }
    }

    public record UpdateSubscriptionRequest(SubscriptionFields subscription) {

        Subscription toDomain() {
            return subscription.toDomain(null);
        }
    }
}
