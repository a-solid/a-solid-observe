package com.imsw.observe.alerting.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.alerting.domain.AlertStatus;
import com.imsw.observe.alerting.domain.EvidenceEntity;
import com.imsw.observe.alerting.infrastructure.evidence.AnnotationRenderer;
import com.imsw.observe.alerting.infrastructure.evidence.EvidenceCollector;
import com.imsw.observe.alerting.infrastructure.fingerprint.AlertFingerprintCalculator;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertMapper;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertPo;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceMapper;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidencePo;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceRepository;
import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.alert.model.Severity;
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.ExecutionMeta;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

public final class DefaultAlertSink implements com.imsw.observe.kernel.alert.spi.AlertSink {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAlertSink.class);

    private final AlertRepository alertRepository;

    private final EvidenceRepository evidenceRepository;

    private final EvidenceCollector evidenceCollector;

    private final AnnotationRenderer annotationRenderer;

    private final SnowflakeIdGenerator snowflake;

    public DefaultAlertSink(
            final AlertRepository alertRepository,
            final EvidenceRepository evidenceRepository,
            final EvidenceCollector evidenceCollector,
            final AnnotationRenderer annotationRenderer,
            final SnowflakeIdGenerator snowflake) {
        this.alertRepository = alertRepository;
        this.evidenceRepository = evidenceRepository;
        this.evidenceCollector = evidenceCollector;
        this.annotationRenderer = annotationRenderer;
        this.snowflake = snowflake;
    }

    @Override
    public void drainAndPersist(final ExecutionContext ctx) {
        List<AlertSignal> signals = ctx.data().drainNewAlerts();
        if (signals.isEmpty()) {
            return;
        }
        ctx.data().emittedAlert = true;
        for (AlertSignal signal : signals) {
            persist(signal, ctx);
        }
    }

    private void persist(final AlertSignal signal, final ExecutionContext ctx) {
        ExecutionMeta meta = ctx.meta();
        Map<String, String> labels = signal.labels() == null ? Map.of() : signal.labels();
        String fingerprint = signal.fingerprint() != null
                ? signal.fingerprint()
                : AlertFingerprintCalculator.compute(meta.pipelineId(), labels);
        Duration ttl = signal.ttl() != null ? signal.ttl() : defaultTtl(signal.severity());
        Instant now = Instant.now();
        Instant endsAt = now.plus(ttl);

        Optional<AlertPo> existing = alertRepository.findFirstByFingerprintAndStatusOrderByIdAsc(fingerprint, "FIRING");
        if (existing.isPresent()) {
            alertRepository.updateEmit(existing.get().id, now, endsAt);
            LOG.info(
                    "alert deduped pipeline={} fingerprint={} dedup_count={}",
                    meta.pipelineId(),
                    fingerprint,
                    existing.get().dedupCount + 1);
            return;
        }
        Map<String, String> annotations = annotationRenderer.render(signal.annotations(), ctx);
        EvidenceCollector.Collected evidence = evidenceCollector.collect(signal, ctx);
        AlertEntity entity = buildAlertEntity(signal, meta, labels, annotations, fingerprint, now, endsAt);
        AlertPo alertPo = AlertMapper.toPo(entity);
        alertRepository.save(alertPo);
        persistEvidence(alertPo.id, evidence.entity(), evidence.sizeBytes(), evidence.truncated());
        LOG.info(
                "alert inserted pipeline={} fingerprint={} severity={}",
                meta.pipelineId(),
                fingerprint,
                signal.severity());
    }

    private AlertEntity buildAlertEntity(
            final AlertSignal signal,
            final ExecutionMeta meta,
            final Map<String, String> labels,
            final Map<String, String> annotations,
            final String fingerprint,
            final Instant now,
            final Instant endsAt) {
        return new AlertEntity(
                snowflake.next(),
                meta.namespace(),
                meta.team(),
                meta.application(),
                meta.pipelineLabels(),
                meta.pipelineId(),
                meta.pipelineVersion(),
                meta.executionId(),
                fingerprint,
                signal.severity(),
                labels,
                annotations,
                now,
                now,
                endsAt,
                null,
                AlertStatus.FIRING,
                1,
                null,
                meta.traceId());
    }

    private void persistEvidence(
            final Long alertId, final EvidenceEntity entity, final int sizeBytes, final boolean truncated) {
        EvidencePo po = EvidenceMapper.toPo(entity);
        po.alertId = alertId;
        po.sizeBytes = sizeBytes;
        po.truncated = truncated;
        evidenceRepository.save(po);
    }

    private static Duration defaultTtl(final Severity severity) {
        return switch (severity) {
            case CRITICAL -> Duration.ofMinutes(60);
            case WARNING -> Duration.ofMinutes(30);
            case INFO -> Duration.ofMinutes(15);
        };
    }
}
