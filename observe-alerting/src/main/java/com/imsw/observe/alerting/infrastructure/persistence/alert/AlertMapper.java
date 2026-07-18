package com.imsw.observe.alerting.infrastructure.persistence.alert;

import java.time.Instant;

import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.alerting.domain.AlertStatus;
import com.imsw.observe.kernel.alert.model.Severity;

public final class AlertMapper {

    private AlertMapper() {}

    public static AlertEntity toEntity(final AlertPo po) {
        if (po == null) {
            return null;
        }
        return new AlertEntity(
                po.id,
                po.namespace,
                po.team,
                po.application,
                po.pipelineLabels,
                po.pipelineId,
                po.pipelineVersion == null ? 0 : po.pipelineVersion,
                po.executionId,
                po.fingerprint,
                po.severity == null ? null : Severity.valueOf(po.severity),
                po.labels,
                po.annotations,
                po.startsAt,
                po.lastSeenAt,
                po.endsAt,
                po.resolvedAt,
                po.status == null ? null : AlertStatus.valueOf(po.status),
                po.dedupCount == null ? 0 : po.dedupCount,
                po.ackNote,
                po.ackBy,
                po.ackAt,
                po.traceId);
    }

    public static AlertPo toPo(final AlertEntity entity) {
        if (entity == null) {
            return null;
        }
        AlertPo po = new AlertPo();
        po.id = entity.id();
        po.namespace = entity.namespace();
        po.team = entity.team();
        po.application = entity.application();
        po.pipelineLabels = entity.pipelineLabels();
        po.pipelineId = entity.pipelineId();
        po.pipelineVersion = entity.pipelineVersion();
        po.executionId = entity.executionId();
        po.fingerprint = entity.fingerprint();
        po.severity = entity.severity() == null ? null : entity.severity().name();
        po.labels = entity.labels();
        po.annotations = entity.annotations();
        po.startsAt = entity.startsAt();
        po.lastSeenAt = entity.lastSeenAt();
        po.endsAt = entity.endsAt();
        po.resolvedAt = entity.resolvedAt();
        po.status = entity.status() == null ? null : entity.status().name();
        po.dedupCount = entity.dedupCount();
        po.ackNote = entity.ackNote();
        po.ackBy = entity.ackBy();
        po.ackAt = entity.ackAt();
        po.traceId = entity.traceId();
        Instant now = Instant.now();
        po.createdAt = now;
        po.updatedAt = now;
        return po;
    }
}
