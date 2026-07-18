package com.imsw.observe.alerting.application;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.alerting.domain.EvidenceEntity;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertMapper;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceMapper;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceRepository;

@Service
public class AlertQueryService {

    private static final int DEFAULT_LIMIT = 100;

    private static final int MAX_LIMIT = 1000;

    private final AlertRepository alertRepository;

    private final EvidenceRepository evidenceRepository;

    public AlertQueryService(final AlertRepository alertRepository, final EvidenceRepository evidenceRepository) {
        this.alertRepository = alertRepository;
        this.evidenceRepository = evidenceRepository;
    }

    /**
     * 列表查询，软隔离铁律（ADR-0002）：namespace 必填，行内存过滤（与既有 team/pipelineId 过滤同款；
     * alert/evidence 资源表不对外暴露 BIGINT 物理主键，namespace 仅作软过滤维度）。
     */
    public List<AlertEntity> findAlerts(
            final String namespace, final String status, final String team, final Long pipelineId, final int limit) {
        int safeLimit = sanitizeLimit(limit);
        List<AlertEntity> alerts;
        if (status != null && !status.isBlank()) {
            alerts =
                    alertRepository
                            .findByStatusOrderByIdDesc(status.toUpperCase(), PageRequest.of(0, safeLimit))
                            .stream()
                            .map(AlertMapper::toEntity)
                            .toList();
        } else {
            alerts = alertRepository.findAll(PageRequest.of(0, safeLimit)).stream()
                    .map(AlertMapper::toEntity)
                    .toList();
        }
        return alerts.stream()
                .filter(a -> namespace.equals(a.namespace()))
                .filter(a -> team == null || team.isBlank() || team.equals(a.team()))
                .filter(a -> pipelineId == null || pipelineId.equals(a.pipelineId()))
                .toList();
    }

    /** 单条按 (namespace, id) 软校验：namespace 不匹配返回 empty，由控制层映射为 404。 */
    public Optional<AlertEntity> findById(final String namespace, final Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return alertRepository.findById(id).map(AlertMapper::toEntity).filter(a -> namespace.equals(a.namespace()));
    }

    /** 单条按 (namespace, alertId) 软校验：namespace 不匹配返回 empty。 */
    public Optional<EvidenceEntity> findEvidenceByAlertId(final String namespace, final Long alertId) {
        if (alertId == null) {
            return Optional.empty();
        }
        return evidenceRepository
                .findByAlertId(alertId)
                .map(EvidenceMapper::toEntity)
                .filter(e -> namespace.equals(e.namespace()));
    }

    private static int sanitizeLimit(final int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
