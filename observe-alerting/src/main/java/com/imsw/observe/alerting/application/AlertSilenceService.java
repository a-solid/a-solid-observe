package com.imsw.observe.alerting.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;

import com.imsw.observe.alerting.domain.AlertSilenceEntity;
import com.imsw.observe.alerting.infrastructure.AlertSilenceMatcher;
import com.imsw.observe.alerting.infrastructure.persistence.silence.AlertSilenceMapper;
import com.imsw.observe.alerting.infrastructure.persistence.silence.AlertSilencePo;
import com.imsw.observe.alerting.infrastructure.persistence.silence.AlertSilenceRepository;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

/**
 * Silence CRUD service（ADR-0005 §3）。写入后失效 matcher 缓存。
 */
@Service
public class AlertSilenceService {

    private final AlertSilenceRepository repository;

    private final AlertSilenceMatcher matcher;

    private final SnowflakeIdGenerator snowflake;

    public AlertSilenceService(
            final AlertSilenceRepository repository,
            final AlertSilenceMatcher matcher,
            final SnowflakeIdGenerator snowflake) {
        this.repository = repository;
        this.matcher = matcher;
        this.snowflake = snowflake;
    }

    public AlertSilenceEntity create(final AlertSilenceEntity draft) {
        AlertSilenceEntity entity = new AlertSilenceEntity(
                snowflake.next(),
                draft.namespace(),
                draft.matchType(),
                draft.match(),
                draft.startsAt(),
                draft.endsAt(),
                draft.note(),
                draft.createdBy(),
                Instant.now(),
                Instant.now());
        AlertSilencePo saved = repository.save(AlertSilenceMapper.toPo(entity));
        matcher.invalidate(entity.namespace());
        return AlertSilenceMapper.toEntity(saved);
    }

    public List<AlertSilenceEntity> findAll(final String namespace) {
        return repository.findByNamespaceAndEndsAtAfter(namespace, Instant.now()).stream()
                .map(AlertSilenceMapper::toEntity)
                .toList();
    }

    public void delete(final String namespace, final Long id) {
        AlertSilencePo po = repository.findById(id).orElse(null);
        if (po == null || !namespace.equals(po.namespace)) {
            return;
        }
        repository.deleteById(id);
        matcher.invalidate(namespace);
    }
}
