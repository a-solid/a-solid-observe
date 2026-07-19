package com.imsw.observe.alerting.application;

import java.time.Instant;

import org.springframework.stereotype.Service;

import com.imsw.observe.alerting.domain.AlertDisposition;
import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertMapper;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.kernel.error.ResourceNotFoundException;

/**
 * 告警处置 service（ADR-0005 §4，两维分离后）：ack / ignore 写接口。
 *
 * <p><b>正交语义</b>：disposition（用户处置）独立于 status（系统态）。ack/ignore 只改 {@code disposition} 列，
 * <b>不校验 status、不改 status</b>——任意 status（ACTIVE 或 EXPIRED）的行都能被打 ack/ignore。
 * EXPIRED 行也能 ack（表达"事后我看到了"——本项目 EXPIRED 是 TTL 到点、非条件恢复，事后标记有意义）。
 *
 * <p><b>不设用户手动 close</b>：要"让告警消失"靠 silence（作用于未来同类）或等到期，与业界
 * （Alertmanager/Grafana 都无手动 close）一致。故无 resolve 端点。
 *
 * <p>不存在（id+namespace 不匹配）抛 {@link ResourceNotFoundException}。
 */
@Service
public class AlertDispositionService {

    private final AlertRepository alertRepository;

    public AlertDispositionService(final AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    public AlertEntity acknowledge(final String namespace, final Long id, final String note, final String by) {
        return apply(namespace, id, AlertDisposition.ACKNOWLEDGED, note, by);
    }

    public AlertEntity ignore(final String namespace, final Long id, final String note, final String by) {
        return apply(namespace, id, AlertDisposition.IGNORED, note, by);
    }

    private AlertEntity apply(
            final String namespace,
            final Long id,
            final AlertDisposition disposition,
            final String note,
            final String by) {
        // 先校验行存在且属于本 namespace（软隔离铁律 ADR-0002），再写 disposition。
        alertRepository
                .findById(id)
                .map(AlertMapper::toEntity)
                .filter(a -> namespace.equals(a.namespace()))
                .orElseThrow(
                        () -> new ResourceNotFoundException("alert " + id + " not found in namespace " + namespace));
        Instant now = Instant.now();
        alertRepository.applyDisposition(id, namespace, disposition.name(), note, by, now);
        return AlertMapper.toEntity(alertRepository.findById(id).orElseThrow());
    }
}
