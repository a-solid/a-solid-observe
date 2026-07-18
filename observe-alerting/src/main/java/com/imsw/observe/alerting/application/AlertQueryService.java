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

    public List<AlertEntity> findAlerts(
            final String status, final String team, final String pipelineId, final int limit) {
        int safeLimit = sanitizeLimit(limit);
        Long pipelineIdFilter = parseLong(pipelineId).orElse(null);
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
                .filter(a -> team == null || team.isBlank() || team.equals(a.team()))
                .filter(a -> pipelineIdFilter == null || pipelineIdFilter.equals(a.pipelineId()))
                .toList();
    }

    public Optional<AlertEntity> findById(final String id) {
        return parseLong(id)
                .flatMap(alertId -> alertRepository.findById(alertId).map(AlertMapper::toEntity));
    }

    public Optional<EvidenceEntity> findEvidenceByAlertId(final String alertId) {
        return parseLong(alertId)
                .flatMap(id -> evidenceRepository.findByAlertId(id).map(EvidenceMapper::toEntity));
    }

    private static int sanitizeLimit(final int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * HTTP 接口层（controlplane）仍以 String 透传 BIGINT id；这里在 application 边界做 String→Long 解析，
     * 非法/空值视为 null（查询不命中）。controlplane 迁移完成后可改为直接接收 Long（Task 6）。
     */
    private static java.util.Optional<Long> parseLong(final String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }
}
