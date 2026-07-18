package com.imsw.observe.alerting.infrastructure.persistence.silence;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.alerting.domain.AlertSilenceEntity;
import com.imsw.observe.alerting.domain.AlertSilenceMatchType;

public final class AlertSilenceMapper {

    private AlertSilenceMapper() {}

    public static AlertSilenceEntity toEntity(final AlertSilencePo po) {
        if (po == null) {
            return null;
        }
        return new AlertSilenceEntity(
                po.id,
                po.namespace,
                po.matchType == null ? null : AlertSilenceMatchType.valueOf(po.matchType),
                po.match == null ? Map.of() : po.match,
                po.startsAt,
                po.endsAt,
                po.note,
                po.createdBy,
                po.createdAt,
                po.updatedAt);
    }

    public static AlertSilencePo toPo(final AlertSilenceEntity entity) {
        if (entity == null) {
            return null;
        }
        AlertSilencePo po = new AlertSilencePo();
        po.id = entity.id();
        po.namespace = entity.namespace();
        po.matchType = entity.matchType() == null ? null : entity.matchType().name();
        po.match = entity.match();
        po.startsAt = entity.startsAt();
        po.endsAt = entity.endsAt();
        po.note = entity.note();
        po.createdBy = entity.createdBy();
        Instant now = Instant.now();
        po.createdAt = now;
        po.updatedAt = now;
        return po;
    }
}
