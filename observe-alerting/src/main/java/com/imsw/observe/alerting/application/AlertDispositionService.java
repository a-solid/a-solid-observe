package com.imsw.observe.alerting.application;

import java.time.Instant;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.alerting.domain.AlertStatus;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertMapper;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.kernel.error.ConflictException;
import com.imsw.observe.kernel.error.ResourceNotFoundException;

/**
 * 告警处置 service（ADR-0005 §4）：ack / resolve / ignore 写接口 + 状态转移守卫。
 *
 * <p>合法转移：
 * <ul>
 *   <li>FIRING → ACKNOWLEDGED / IGNORED / RESOLVED（用户手动 close）</li>
 *   <li>ACKNOWLEDGED → RESOLVED</li>
 *   <li>IGNORED → RESOLVED</li>
 *   <li>RESOLVED → 终态</li>
 * </ul>
 * 非法转移抛 {@link ErrorResponseException}(CONFLICT)；不存在抛 {@link ResourceNotFoundException}。
 *
 * <p>更新走 CAS 式 JPQL（带 {@code status=:from} 条件），影响 0 行（并发已转移）抛 CONFLICT。
 */
@Service
public class AlertDispositionService {

    private final AlertRepository alertRepository;

    public AlertDispositionService(final AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    public AlertEntity acknowledge(final String namespace, final Long id, final String note, final String by) {
        return transition(namespace, id, Set.of(AlertStatus.FIRING), AlertStatus.ACKNOWLEDGED, note, by, false);
    }

    public AlertEntity ignore(final String namespace, final Long id, final String note, final String by) {
        return transition(namespace, id, Set.of(AlertStatus.FIRING), AlertStatus.IGNORED, note, by, false);
    }

    public AlertEntity resolve(final String namespace, final Long id, final String note, final String by) {
        return transition(
                namespace,
                id,
                Set.of(AlertStatus.FIRING, AlertStatus.ACKNOWLEDGED, AlertStatus.IGNORED),
                AlertStatus.RESOLVED,
                note,
                by,
                true);
    }

    private AlertEntity transition(
            final String namespace,
            final Long id,
            final Set<AlertStatus> allowedFrom,
            final AlertStatus target,
            final String note,
            final String by,
            final boolean setResolvedAt) {
        AlertEntity alert = alertRepository
                .findById(id)
                .map(AlertMapper::toEntity)
                .filter(a -> namespace.equals(a.namespace()))
                .orElseThrow(
                        () -> new ResourceNotFoundException("alert " + id + " not found in namespace " + namespace));
        if (!allowedFrom.contains(alert.status())) {
            throw new ConflictException("alert " + id + " cannot transition " + alert.status() + " → " + target);
        }
        Instant now = Instant.now();
        int updated = alertRepository.applyDisposition(
                id, namespace, alert.status().name(), target.name(), note, by, now, setResolvedAt ? now : null);
        if (updated == 0) {
            throw new ConflictException("alert " + id + " state changed concurrently");
        }
        return AlertMapper.toEntity(alertRepository.findById(id).orElseThrow());
    }
}
