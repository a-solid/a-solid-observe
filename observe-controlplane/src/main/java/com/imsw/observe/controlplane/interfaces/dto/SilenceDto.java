package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;
import java.util.Map;

import com.imsw.observe.alerting.domain.AlertSilenceEntity;

/** Silence 规则 DTO（ADR-0005 §3）。 */
public record SilenceDto(
        Long id,
        String namespace,
        String matchType,
        Map<String, Object> match,
        Instant startsAt,
        Instant endsAt,
        String note,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static SilenceDto from(final AlertSilenceEntity e) {
        return new SilenceDto(
                e.id(),
                e.namespace(),
                e.matchType() == null ? null : e.matchType().name(),
                e.match(),
                e.startsAt(),
                e.endsAt(),
                e.note(),
                e.createdBy(),
                e.createdAt(),
                e.updatedAt());
    }
}
