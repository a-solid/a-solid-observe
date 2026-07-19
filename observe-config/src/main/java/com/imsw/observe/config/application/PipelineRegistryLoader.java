package com.imsw.observe.config.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.config.infrastructure.ConditionCodec;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionRepository;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionPk;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionRepository;
import com.imsw.observe.config.infrastructure.persistence.SubscriptionPo;
import com.imsw.observe.config.infrastructure.persistence.SubscriptionRepository;
import com.imsw.observe.kernel.util.JsonUtil;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.domain.Pipeline;
import com.imsw.observe.pipeline.domain.subscription.Action;
import com.imsw.observe.pipeline.domain.subscription.Subscription;

public final class PipelineRegistryLoader {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineRegistryLoader.class);

    private final PipelineDefinitionRepository pipelineDefinitionRepository;

    private final PipelineVersionRepository pipelineVersionRepository;

    private final SubscriptionRepository subscriptionRepository;

    private final ConditionCodec conditionCodec;

    public PipelineRegistryLoader(
            final PipelineDefinitionRepository pipelineDefinitionRepository,
            final PipelineVersionRepository pipelineVersionRepository,
            final SubscriptionRepository subscriptionRepository,
            final ConditionCodec conditionCodec) {
        this.pipelineDefinitionRepository = pipelineDefinitionRepository;
        this.pipelineVersionRepository = pipelineVersionRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.conditionCodec = conditionCodec;
    }

    public PipelineRegistry.Snapshot load() {
        Map<Long, Pipeline> pipelines = new HashMap<>();
        List<Subscription> subscriptions = new ArrayList<>();
        for (PipelineDefinitionPo defPo : pipelineDefinitionRepository.findAll()) {
            if (defPo.currentVersion == null) {
                continue;
            }
            PipelineVersionPo versionPo = pipelineVersionRepository
                    .findById(new PipelineVersionPk(defPo.id, defPo.currentVersion))
                    .orElse(null);
            if (versionPo == null || !"PUBLISHED".equals(versionPo.status)) {
                continue;
            }
            Pipeline pipeline = deserialize(versionPo);
            if (pipeline == null) {
                continue;
            }
            pipelines.put(pipeline.id(), pipeline);
        }
        for (SubscriptionPo subPo : subscriptionRepository.findAll()) {
            // 扇出：pipelineIds 至少一个 id 在已加载 pipelines 里才纳入订阅；全部缺失则跳过。
            if ("ACTIVE".equals(subPo.status) && anyPipelinePresent(subPo, pipelines)) {
                subscriptions.add(toPipelineSubscription(subPo));
            }
        }
        LOG.info("registry loaded: {} pipelines, {} subscriptions", pipelines.size(), subscriptions.size());
        return PipelineRegistry.Snapshot.loaded(pipelines, subscriptions);
    }

    /** 扇出纳入判定：subscription 的 pipelineIds 至少一个 id 在已加载 pipelines 里。 */
    private static boolean anyPipelinePresent(final SubscriptionPo subPo, final Map<Long, Pipeline> pipelines) {
        if (subPo.pipelineIds == null || subPo.pipelineIds.isEmpty()) {
            return false;
        }
        for (Long pid : subPo.pipelineIds) {
            if (pipelines.containsKey(pid)) {
                return true;
            }
        }
        return false;
    }

    private Pipeline deserialize(final PipelineVersionPo versionPo) {
        try {
            Pipeline pipeline = JsonUtil.fromJson(versionPo.definitionJson, Pipeline.class);
            if (pipeline == null) {
                return null;
            }
            // namespace invariant：saveDraft 已保证 definitionJson 内嵌 namespace 非空且与版本 PO 一致
            // （软隔离铁律 ADR-0002）。body namespace null/blank 视为数据损坏——拒绝加载（warn + skip），
            // 不再用版本 PO 的 namespace 兜底重建（旧 11 参 positional 重建脆且为旧数据迁移兜底，项目未上线无此场景）。
            if (pipeline.namespace() == null || pipeline.namespace().isBlank()) {
                LOG.warn(
                        "pipeline {} version {} has blank namespace in definitionJson, skipping",
                        versionPo.pipelineId,
                        versionPo.version);
                return null;
            }
            return pipeline;
        } catch (RuntimeException e) {
            LOG.warn("failed to deserialize pipeline {} version {}", versionPo.pipelineId, versionPo.version, e);
            return null;
        }
    }

    private Subscription toPipelineSubscription(final SubscriptionPo po) {
        com.imsw.observe.config.domain.SubscriptionDefinition entity =
                com.imsw.observe.config.infrastructure.persistence.SubscriptionMapper.toEntity(po, conditionCodec);
        Subscription.SourceRef source = new Subscription.SourceRef(
                entity.db(),
                entity.table(),
                entity.opTypes(),
                entity.sourceType(),
                entity.cronExpression(),
                toSourceConcurrent(entity.concurrent()));
        return new Subscription(
                entity.id(),
                entity.namespace(),
                entity.name(),
                entity.pipelineIds(),
                source,
                entity.fieldFilter(),
                toAction(entity));
    }

    private static Subscription.SourceRef.Concurrent toSourceConcurrent(
            final com.imsw.observe.config.domain.SubscriptionDefinition.Concurrent concurrent) {
        if (concurrent == null) {
            return null;
        }
        return switch (concurrent) {
            case SKIP -> Subscription.SourceRef.Concurrent.SKIP;
            case ALLOW -> Subscription.SourceRef.Concurrent.ALLOW;
        };
    }

    private static Action toAction(final com.imsw.observe.config.domain.SubscriptionDefinition entity) {
        if (entity.actionType() == null) {
            return new Action.Run();
        }
        return switch (entity.actionType()) {
            case RUN -> new Action.Run();
            case SCHEDULE -> new Action.Schedule(entity.scheduleDelay(), entity.scheduleCorrelationKeyPath());
            case CANCEL -> new Action.Cancel(entity.scheduleCorrelationKeyPath());
        };
    }
}
