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

    private final NamespaceCrudService namespaceCrudService;

    public SubscriptionCrudService(
            final SubscriptionRepository repository,
            final PipelineDefinitionRepository pipelineDefinitionRepository,
            final PipelineVersionRepository pipelineVersionRepository,
            final ConditionCodec conditionCodec,
            final SnowflakeIdGenerator snowflakeIdGenerator,
            final NamespaceCrudService namespaceCrudService) {
        this.repository = repository;
        this.pipelineDefinitionRepository = pipelineDefinitionRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.conditionCodec = conditionCodec;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.namespaceCrudService = namespaceCrudService;
    }

    @Transactional
    public Subscription create(final Subscription subscription) {
        // 软隔离铁律：namespace 必须存在；subscription.namespace 决定归属。
        if (namespaceCrudService.findByName(subscription.namespace()) == null) {
            throw new IllegalArgumentException("namespace not found: " + subscription.namespace());
        }
        // 业务键非空校验：subscriptions.name 为 NOT NULL（参考 V1__init.sql），与 pipelines.name 对称。
        // (namespace, name) 唯一索引在 NULL-name 上不生效，需在应用层挡住空 name，避免产生不可寻址/不可删除的行。
        if (subscription.name() == null || subscription.name().isBlank()) {
            throw new IllegalArgumentException("subscription name must not be blank");
        }
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
    public Subscription update(final String namespace, final String name, final Subscription subscription) {
        SubscriptionPo existing = repository
                .findByNamespaceAndName(namespace, name)
                .orElseThrow(() -> new IllegalArgumentException("subscription not found: " + namespace + "/" + name));
        validatePipeline(subscription);
        SubscriptionPo po = SubscriptionMapper.toPo(subscription, conditionCodec);
        po.id = existing.id;
        po.namespace = existing.namespace;
        po.createdAt = existing.createdAt;
        po.updatedAt = Instant.now();
        SubscriptionPo saved = repository.save(po);
        return SubscriptionMapper.toEntity(saved, conditionCodec);
    }

    @Transactional(readOnly = true)
    public Subscription find(final String namespace, final String name) {
        return repository
                .findByNamespaceAndName(namespace, name)
                .map(po -> SubscriptionMapper.toEntity(po, conditionCodec))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Subscription> findAll(final String namespace) {
        return repository.findAllByNamespace(namespace).stream()
                .map(po -> SubscriptionMapper.toEntity(po, conditionCodec))
                .toList();
    }

    @Transactional
    public void delete(final String namespace, final String name) {
        SubscriptionPo po = repository
                .findByNamespaceAndName(namespace, name)
                .orElseThrow(() -> new IllegalArgumentException("subscription not found: " + namespace + "/" + name));
        repository.delete(po);
    }

    private void validatePipeline(final Subscription subscription) {
        PipelineDefinitionPo def = pipelineDefinitionRepository
                .findById(subscription.pipelineId())
                .orElseThrow(() -> new IllegalArgumentException("pipeline not found: " + subscription.pipelineId()));
        // 软隔离铁律（ADR-0002）：subscription 只能引用同 namespace 下的 pipeline。
        if (!def.namespace.equals(subscription.namespace())) {
            throw new IllegalArgumentException("pipeline " + subscription.pipelineId()
                    + " does not belong to namespace " + subscription.namespace());
        }
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
