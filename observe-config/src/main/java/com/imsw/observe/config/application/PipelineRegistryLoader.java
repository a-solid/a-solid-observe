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
        Map<String, Pipeline> pipelines = new HashMap<>();
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
            if (!"ACTIVE".equals(subPo.status)) {
                continue;
            }
            if (!pipelines.containsKey(subPo.pipelineId)) {
                continue;
            }
            subscriptions.add(toPipelineSubscription(subPo));
        }
        LOG.info("registry loaded: {} pipelines, {} subscriptions", pipelines.size(), subscriptions.size());
        return PipelineRegistry.Snapshot.loaded(pipelines, subscriptions);
    }

    private Pipeline deserialize(final PipelineVersionPo versionPo) {
        try {
            return JsonUtil.fromJson(versionPo.definitionJson, Pipeline.class);
        } catch (RuntimeException e) {
            LOG.warn("failed to deserialize pipeline {} version {}", versionPo.pipelineId, versionPo.version, e);
            return null;
        }
    }

    private Subscription toPipelineSubscription(final SubscriptionPo po) {
        com.imsw.observe.config.domain.Subscription entity =
                com.imsw.observe.config.infrastructure.persistence.SubscriptionMapper.toEntity(po, conditionCodec);
        Subscription.SourceRef source = new Subscription.SourceRef(
                entity.mq(), entity.topic(), entity.db(), entity.table(), entity.opTypes(), entity.sourceType());
        return new Subscription(
                entity.id(),
                entity.pipelineId(),
                entity.pipelineVersion(),
                source,
                entity.fieldFilter(),
                toAction(entity));
    }

    private static Action toAction(final com.imsw.observe.config.domain.Subscription entity) {
        if (entity.actionType() == null) {
            return new Action.Run();
        }
        return switch (entity.actionType()) {
            case RUN -> new Action.Run();
            case SCHEDULE -> new Action.Schedule(
                    entity.scheduleDelay(), entity.scheduleCorrelationKeyPath(), entity.pipelineId());
            case CANCEL -> new Action.Cancel(entity.scheduleCorrelationKeyPath());
        };
    }
}
