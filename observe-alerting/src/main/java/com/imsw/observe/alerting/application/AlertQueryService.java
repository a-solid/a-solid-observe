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
            final String status, final String team, final Long pipelineId, final int limit) {
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
                .filter(a -> team == null || team.isBlank() || team.equals(a.team()))
                .filter(a -> pipelineId == null || pipelineId.equals(a.pipelineId()))
                .toList();
    }

    public Optional<AlertEntity> findById(final Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return alertRepository.findById(id).map(AlertMapper::toEntity);
    }

    public Optional<EvidenceEntity> findEvidenceByAlertId(final Long alertId) {
        if (alertId == null) {
            return Optional.empty();
        }
        return evidenceRepository.findByAlertId(alertId).map(EvidenceMapper::toEntity);
    }

    private static int sanitizeLimit(final int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
