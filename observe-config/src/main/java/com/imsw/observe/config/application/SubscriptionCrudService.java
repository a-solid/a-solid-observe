package com.imsw.observe.config.application;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imsw.observe.config.domain.SubscriptionDefinition;
import com.imsw.observe.config.infrastructure.ConditionCodec;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionRepository;
import com.imsw.observe.config.infrastructure.persistence.SubscriptionMapper;
import com.imsw.observe.config.infrastructure.persistence.SubscriptionPo;
import com.imsw.observe.config.infrastructure.persistence.SubscriptionRepository;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

@Service
public class SubscriptionCrudService {

    private final SubscriptionRepository repository;

    private final PipelineDefinitionRepository pipelineDefinitionRepository;

    private final ConditionCodec conditionCodec;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    private final NamespaceCrudService namespaceCrudService;

    public SubscriptionCrudService(
            final SubscriptionRepository repository,
            final PipelineDefinitionRepository pipelineDefinitionRepository,
            final ConditionCodec conditionCodec,
            final SnowflakeIdGenerator snowflakeIdGenerator,
            final NamespaceCrudService namespaceCrudService) {
        this.repository = repository;
        this.pipelineDefinitionRepository = pipelineDefinitionRepository;
        this.conditionCodec = conditionCodec;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.namespaceCrudService = namespaceCrudService;
    }

    @Transactional
    public SubscriptionDefinition create(final SubscriptionDefinition subscription) {
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
        validateCron(subscription);
        validateAction(subscription);
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
    public SubscriptionDefinition update(
            final String namespace, final String name, final SubscriptionDefinition subscription) {
        SubscriptionPo existing = repository
                .findByNamespaceAndName(namespace, name)
                .orElseThrow(() -> new IllegalArgumentException("subscription not found: " + namespace + "/" + name));
        validatePipeline(subscription);
        validateCron(subscription);
        validateAction(subscription);
        SubscriptionPo po = SubscriptionMapper.toPo(subscription, conditionCodec);
        po.id = existing.id;
        po.namespace = existing.namespace;
        po.createdAt = existing.createdAt;
        po.updatedAt = Instant.now();
        SubscriptionPo saved = repository.save(po);
        return SubscriptionMapper.toEntity(saved, conditionCodec);
    }

    @Transactional(readOnly = true)
    public SubscriptionDefinition find(final String namespace, final String name) {
        return repository
                .findByNamespaceAndName(namespace, name)
                .map(po -> SubscriptionMapper.toEntity(po, conditionCodec))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionDefinition> findAll(final String namespace) {
        return repository.findAllByNamespace(namespace).stream()
                .map(po -> SubscriptionMapper.toEntity(po, conditionCodec))
                .toList();
    }

    @Transactional
    public void delete(final String namespace, final String name) {
        SubscriptionPo po = requireSubscription(namespace, name);
        repository.delete(po);
    }

    /**
     * 停用订阅（status → INACTIVE）。loader 热加载时只收 ACTIVE 订阅（{@code PipelineRegistryLoader.load}），
     * INACTIVE 订阅不进运行态 registry → matcher 天然路由不到、不触发 pipeline。与 delete 的区别：保留配置、
     * 可经 {@link #activate} 恢复。生效延迟 = 下次热加载 poll（≤30s），不主动触发 refresh。
     */
    @Transactional
    public SubscriptionDefinition deactivate(final String namespace, final String name) {
        SubscriptionPo po = requireSubscription(namespace, name);
        po.status = "INACTIVE";
        po.updatedAt = Instant.now();
        return SubscriptionMapper.toEntity(repository.save(po), conditionCodec);
    }

    /** 启用订阅（status → ACTIVE）。下次热加载 poll（≤30s）后重新进 registry 生效。 */
    @Transactional
    public SubscriptionDefinition activate(final String namespace, final String name) {
        SubscriptionPo po = requireSubscription(namespace, name);
        po.status = "ACTIVE";
        po.updatedAt = Instant.now();
        return SubscriptionMapper.toEntity(repository.save(po), conditionCodec);
    }

    private SubscriptionPo requireSubscription(final String namespace, final String name) {
        return repository
                .findByNamespaceAndName(namespace, name)
                .orElseThrow(() -> new IllegalArgumentException("subscription not found: " + namespace + "/" + name));
    }

    private void validatePipeline(final SubscriptionDefinition subscription) {
        if (subscription.pipelineIds() == null || subscription.pipelineIds().isEmpty()) {
            throw new IllegalArgumentException("pipelineIds must not be empty");
        }
        for (Long pipelineId : subscription.pipelineIds()) {
            PipelineDefinitionPo def = pipelineDefinitionRepository
                    .findById(pipelineId)
                    .orElseThrow(() -> new IllegalArgumentException("pipeline not found: " + pipelineId));
            // 软隔离铁律（ADR-0002）：subscription 只能引用同 namespace 下的 pipeline。
            if (!def.namespace.equals(subscription.namespace())) {
                throw new IllegalArgumentException(
                        "pipeline " + pipelineId + " does not belong to namespace " + subscription.namespace());
            }
            if (!"PUBLISHED".equals(def.status)) {
                throw new IllegalArgumentException("pipeline not published: " + pipelineId);
            }
        }
    }

    /**
     * Cron 订阅校验（B4, ADR-0007）。
     *
     * <p>当 {@code sourceType == CRON} 时：
     * <ul>
     *   <li>{@code cronExpression} 必须非空/非空白，且必须可被 Spring {@link CronExpression} 解析
     *       （解析失败抛 {@link IllegalArgumentException}，此处转译为清晰错误）。</li>
     *   <li>{@code mq}/{@code cronName} 不变性：CRON 订阅的索引键来源是 {@code mq}（见
     *       {@code PipelineRegistry.Snapshot.subscriptionsBySource} 以 {@code sub.source().mq()} 为 key），
     *       而 {@code CronSource.dispatch} 用 {@code mq} 作为 {@code TickMeta.source} 路由键。若
     *       {@code cronName} 非 null 且与 {@code mq} 不一致，会在"索引键 vs 逻辑名"间产生二义性——
     *       此处从源头挡住：{@code cronName == null || cronName.equals(mq)}（即 cronName 缺省时以 mq 为
     *       规范名；给定则必须与 mq 严格一致）。参考 B4-T3 review Finding #2。</li>
     * </ul>
     * 当 {@code sourceType != CRON} 时：cron 字段对其它源类型无意义，{@code cronExpression} 必须为空
     * （避免 CRON 配置泄漏到 CDC/API 订阅造成歧义）。{@code concurrent} 默认 SKIP 由调用方/Loader 处理。
     */
    private void validateCron(final SubscriptionDefinition subscription) {
        if (subscription.sourceType() == SourceType.CRON) {
            if (subscription.cronExpression() == null
                    || subscription.cronExpression().isBlank()) {
                throw new IllegalArgumentException("cronExpression must not be blank when sourceType is CRON");
            }
            try {
                CronExpression.parse(subscription.cronExpression());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "invalid cronExpression '" + subscription.cronExpression() + "': " + e.getMessage(), e);
            }
            // mq/cronName 不变性（B4-T3 Finding #2）：cronName 非 null 时必须等于 mq——
            // mq 是 Snapshot 索引键与 TickMeta.source 的来源，cronName 二义会导致路由失败。
            String mq = subscription.mq();
            String cronName = subscription.cronName();
            if (cronName != null && !cronName.equals(mq)) {
                throw new IllegalArgumentException(
                        "CRON subscription mq and cronName must match; got mq=" + mq + " cronName=" + cronName);
            }
        } else if (subscription.cronExpression() != null
                && !subscription.cronExpression().isBlank()) {
            throw new IllegalArgumentException("cronExpression must be null when sourceType is "
                    + subscription.sourceType() + " (only CRON subscriptions use cronExpression)");
        }
    }

    /**
     * Action 校验（delayed-redesign spec D1/D2）。
     *
     * <p>三种 actionType 平权，订阅选其一。约束：
     * <ul>
     *   <li>{@code SCHEDULE}：{@code scheduleDelay} 必填、{@code scheduleCorrelationKeyPath} 必填非空白。</li>
     *   <li>{@code CANCEL}：{@code scheduleCorrelationKeyPath} 必填非空白（{@code scheduleDelay} 必为 null）。</li>
     *   <li>{@code RUN}（或 null）：{@code scheduleDelay} 与 {@code scheduleCorrelationKeyPath} 必为 null。</li>
     * </ul>
     *
     * <p>schedule 和 cancel 不强制配对——业务方通过 keyPath 运行期对齐。
     */
    private void validateAction(final SubscriptionDefinition subscription) {
        SubscriptionDefinition.ActionType type = subscription.actionType();
        if (type == null || type == SubscriptionDefinition.ActionType.RUN) {
            validateRunAction(subscription);
        } else if (type == SubscriptionDefinition.ActionType.SCHEDULE) {
            validateScheduleAction(subscription);
        } else {
            validateCancelAction(subscription);
        }
    }

    private static void validateRunAction(final SubscriptionDefinition subscription) {
        if (subscription.scheduleDelay() != null) {
            throw new IllegalArgumentException("scheduleDelay must be null when actionType is RUN");
        }
        if (subscription.scheduleCorrelationKeyPath() != null
                && !subscription.scheduleCorrelationKeyPath().isBlank()) {
            throw new IllegalArgumentException("scheduleCorrelationKeyPath must be null when actionType is RUN");
        }
    }

    private static void validateScheduleAction(final SubscriptionDefinition subscription) {
        if (subscription.scheduleDelay() == null) {
            throw new IllegalArgumentException("scheduleDelay must not be null when actionType is SCHEDULE");
        }
        if (subscription.scheduleDelay().isZero()
                || subscription.scheduleDelay().isNegative()) {
            throw new IllegalArgumentException("scheduleDelay must be positive when actionType is SCHEDULE");
        }
        requireCorrelationKeyPath(subscription, "SCHEDULE");
    }

    private static void validateCancelAction(final SubscriptionDefinition subscription) {
        if (subscription.scheduleDelay() != null) {
            throw new IllegalArgumentException("scheduleDelay must be null when actionType is CANCEL");
        }
        requireCorrelationKeyPath(subscription, "CANCEL");
    }

    private static void requireCorrelationKeyPath(final SubscriptionDefinition subscription, final String actionType) {
        if (subscription.scheduleCorrelationKeyPath() == null
                || subscription.scheduleCorrelationKeyPath().isBlank()) {
            throw new IllegalArgumentException(
                    "scheduleCorrelationKeyPath must not be blank when actionType is " + actionType);
        }
    }
}
