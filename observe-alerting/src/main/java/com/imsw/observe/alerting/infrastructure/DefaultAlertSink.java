package com.imsw.observe.alerting.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.alerting.domain.ActiveWave;
import com.imsw.observe.alerting.domain.AlertDisposition;
import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.alerting.domain.AlertStatus;
import com.imsw.observe.alerting.domain.EvidenceEntity;
import com.imsw.observe.alerting.domain.WaveDecision;
import com.imsw.observe.alerting.domain.WavePolicy;
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
import com.imsw.observe.kernel.event.model.ExecutionContext;
import com.imsw.observe.kernel.event.model.ExecutionMeta;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

/**
 * 告警落库（ADR-0005 wave + 1:N evidence + silence 拦截）。
 *
 * <p>每次 emit（含 wave 内 dedup 命中）都写一条 evidence（1:N）。emit 前查 silence matcher，命中则不建告警。
 */
public final class DefaultAlertSink implements com.imsw.observe.kernel.alert.spi.AlertSink {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAlertSink.class);

    private final AlertRepository alertRepository;

    private final EvidenceRepository evidenceRepository;

    private final EvidenceCollector evidenceCollector;

    private final AnnotationRenderer annotationRenderer;

    private final AlertSilenceMatcher silenceMatcher;

    private final SnowflakeIdGenerator snowflake;

    private final WavePolicy wavePolicy;

    public DefaultAlertSink(
            final AlertRepository alertRepository,
            final EvidenceRepository evidenceRepository,
            final EvidenceCollector evidenceCollector,
            final AnnotationRenderer annotationRenderer,
            final AlertSilenceMatcher silenceMatcher,
            final SnowflakeIdGenerator snowflake,
            final WavePolicy wavePolicy) {
        this.alertRepository = alertRepository;
        this.evidenceRepository = evidenceRepository;
        this.evidenceCollector = evidenceCollector;
        this.annotationRenderer = annotationRenderer;
        this.silenceMatcher = silenceMatcher;
        this.snowflake = snowflake;
        this.wavePolicy = wavePolicy;
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
        // ADR-0004 / spec §5.3：mergedLabels = merge(meta.labels /*pipeline 打底*/, signal.labels /*脚本覆盖*/)。
        // 脚本 key 优先（脚本可显式覆盖 pipeline 的 team/app/line 等）。signal.labels 可能为 null
        // （AlertsApi 简化 API：critical/warning/info 不传 labels）→ 视为空覆盖。
        Map<String, String> mergedLabels = mergeLabels(meta.labels(), signal.labels());
        String fingerprint = signal.fingerprint() != null
                ? signal.fingerprint()
                : AlertFingerprintCalculator.compute(meta.pipelineId(), mergedLabels);

        // ADR-0005 §3：emit 前查 silence，命中则静默（不建告警、不写证据）
        if (silenceMatcher.silenced(meta.namespace(), fingerprint, mergedLabels, meta.pipelineId())) {
            return;
        }

        Instant now = Instant.now();
        // 波次决策（WavePolicy，纯函数）：现有 ACTIVE 行 → 续期；无 → 开新波次。"新波次=新行"由此显式。
        Optional<AlertPo> existing =
                alertRepository.findFirstByFingerprintAndStatusOrderByIdAsc(fingerprint, AlertStatus.ACTIVE.name());
        WaveDecision decision = wavePolicy.decide(existing.map(po -> new ActiveWave(po.id, po.endsAt)), signal, now);
        EvidenceCollector.Collected evidence = evidenceCollector.collect(signal, ctx);

        if (decision instanceof WaveDecision.Extend extend) {
            // wave 内 dedup：刷新 wave + 写一条新证据（1:N）
            alertRepository.updateEmit(extend.existingId(), now, extend.newEndsAt());
            writeEvidence(extend.existingId(), evidence, now);
            int dedupCount = existing.map(po -> po.dedupCount == null ? 1 : po.dedupCount + 1)
                    .orElse(1);
            LOG.info(
                    "alert deduped pipeline={} fingerprint={} dedup_count={}",
                    meta.pipelineId(),
                    fingerprint,
                    dedupCount);
            return;
        }
        // WaveDecision.Open：开新波次
        WaveDecision.Open open = (WaveDecision.Open) decision;
        Map<String, String> annotations = annotationRenderer.render(signal.annotations(), ctx);
        AlertEntity entity =
                buildAlertEntity(signal, meta, mergedLabels, annotations, fingerprint, now, open.newEndsAt());
        AlertPo alertPo = AlertMapper.toPo(entity);
        alertRepository.save(alertPo);
        writeEvidence(alertPo.id, evidence, now);
        LOG.info(
                "alert inserted pipeline={} fingerprint={} severity={}",
                meta.pipelineId(),
                fingerprint,
                signal.severity());
    }

    /** ADR-0004 / spec §5.3：pipeline labels 打底 + 脚本 labels 覆盖（脚本 key 胜）。 */
    private static Map<String, String> mergeLabels(
            final Map<String, String> pipelineLabels, final Map<String, String> scriptLabels) {
        Map<String, String> base =
                pipelineLabels == null || pipelineLabels.isEmpty() ? Map.of() : new java.util.HashMap<>(pipelineLabels);
        if (scriptLabels == null || scriptLabels.isEmpty()) {
            return base;
        }
        // base 可能为不可变 Map.of()；脚本要覆盖就必须改成可变副本
        Map<String, String> merged = base.isEmpty() ? new java.util.HashMap<>() : new java.util.HashMap<>(base);
        merged.putAll(scriptLabels);
        return merged;
    }

    private void writeEvidence(
            final Long alertId, final EvidenceCollector.Collected evidence, final Instant capturedAt) {
        EvidenceEntity entity = evidence.entity();
        int emitSeq = (int) evidenceRepository.countByAlertId(alertId) + 1;
        EvidenceEntity withMeta = new EvidenceEntity(
                snowflake.next(),
                alertId,
                entity.namespace(),
                entity.pipelineId(),
                entity.pipelineVersion(),
                entity.executionId(),
                entity.nodeName(),
                entity.outputs(),
                entity.traceId(),
                entity.spanId(),
                capturedAt,
                evidence.truncated(),
                emitSeq);
        EvidencePo po = EvidenceMapper.toPo(withMeta);
        evidenceRepository.save(po);
    }

    private AlertEntity buildAlertEntity(
            final AlertSignal signal,
            final ExecutionMeta meta,
            final Map<String, String> labels,
            final Map<String, String> annotations,
            final String fingerprint,
            final Instant now,
            final Instant endsAt) {
        // ADR-0004 §2 / spec §5.3：label_* 投影列从 mergedLabels 同步投影，缺失为 null。
        return new AlertEntity(
                snowflake.next(),
                meta.namespace(),
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
                AlertStatus.ACTIVE,
                AlertDisposition.NONE,
                1,
                null,
                null,
                null,
                meta.traceId(),
                labels == null ? null : labels.get("team"),
                labels == null ? null : labels.get("app"),
                labels == null ? null : labels.get("line"));
    }
}
