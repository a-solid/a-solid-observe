package com.imsw.observe.config.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imsw.observe.config.domain.Subscription;
import com.imsw.observe.config.infrastructure.ConditionCodec;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionRepository;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionPk;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionRepository;
import com.imsw.observe.config.infrastructure.persistence.SubscriptionMapper;
import com.imsw.observe.config.infrastructure.persistence.SubscriptionPo;
import com.imsw.observe.config.infrastructure.persistence.SubscriptionRepository;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

@Service
public class SubscriptionCrudService {

    private final SubscriptionRepository repository;

    private final PipelineDefinitionRepository pipelineDefinitionRepository;

    private final PipelineVersionRepository pipelineVersionRepository;

    private final ConditionCodec conditionCodec;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public SubscriptionCrudService(
            final SubscriptionRepository repository,
            final PipelineDefinitionRepository pipelineDefinitionRepository,
            final PipelineVersionRepository pipelineVersionRepository,
            final ConditionCodec conditionCodec,
            final SnowflakeIdGenerator snowflakeIdGenerator) {
        this.repository = repository;
        this.pipelineDefinitionRepository = pipelineDefinitionRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.conditionCodec = conditionCodec;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Transactional
    public Subscription create(final Subscription subscription) {
        validatePipeline(subscription);
        SubscriptionPo po = SubscriptionMapper.toPo(subscription, conditionCodec);
        po.id = snowflakeIdGenerator.next();
        Instant now = Instant.now();
        po.createdAt = now;
        po.updatedAt = now;
        if (po.status == null) {
            po.status = "ACTIVE";
        }
        if (po.actionType == null) {
            po.actionType = "RUN";
        }
        SubscriptionPo saved = repository.save(po);
        return SubscriptionMapper.toEntity(saved, conditionCodec);
    }

    @Transactional
    public Subscription update(final Long id, final Subscription subscription) {
        SubscriptionPo existing = repository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("subscription not found: " + id));
        validatePipeline(subscription);
        SubscriptionPo po = SubscriptionMapper.toPo(subscription, conditionCodec);
        po.id = existing.id;
        po.createdAt = existing.createdAt;
        po.updatedAt = Instant.now();
        SubscriptionPo saved = repository.save(po);
        return SubscriptionMapper.toEntity(saved, conditionCodec);
    }

    @Transactional(readOnly = true)
    public Subscription find(final Long id) {
        return repository
                .findById(id)
                .map(po -> SubscriptionMapper.toEntity(po, conditionCodec))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Subscription> findAll() {
        return repository.findAll().stream()
                .map(po -> SubscriptionMapper.toEntity(po, conditionCodec))
                .toList();
    }

    @Transactional
    public void delete(final Long id) {
        repository.deleteById(id);
    }

    private void validatePipeline(final Subscription subscription) {
        PipelineDefinitionPo def = pipelineDefinitionRepository
                .findById(subscription.pipelineId())
                .orElseThrow(() -> new IllegalArgumentException("pipeline not found: " + subscription.pipelineId()));
        if (!"PUBLISHED".equals(def.status)) {
            throw new IllegalArgumentException("pipeline not published: " + subscription.pipelineId());
        }
        PipelineVersionPo version = pipelineVersionRepository
                .findById(new PipelineVersionPk(subscription.pipelineId(), subscription.pipelineVersion()))
                .orElseThrow(() -> new IllegalArgumentException("pipeline version not found: "
                        + subscription.pipelineId() + " v" + subscription.pipelineVersion()));
        if (!"PUBLISHED".equals(version.status)) {
            throw new IllegalArgumentException("pipeline version not published: " + subscription.pipelineId() + " v"
                    + subscription.pipelineVersion());
        }
    }
}
