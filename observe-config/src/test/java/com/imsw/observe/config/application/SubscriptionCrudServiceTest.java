package com.imsw.observe.config.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.imsw.observe.config.domain.Namespace;
import com.imsw.observe.config.domain.Subscription;
import com.imsw.observe.config.infrastructure.ConditionCodec;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionRepository;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionRepository;
import com.imsw.observe.config.infrastructure.persistence.SubscriptionRepository;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

class SubscriptionCrudServiceTest {

    @Test
    void createRejectsSubscriptionWhoseNamespaceDiffersFromPipeline() {
        // 软隔离铁律（ADR-0002）：subscription 只能引用同 namespace 下的 pipeline。
        // 覆盖 e110cc7：validatePipeline 必须在 def.namespace != subscription.namespace 时拒绝，
        // 即便 subscription 自身的 namespace 在 namespaces 表里存在。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        PipelineVersionRepository pipelineVersionRepo = mock(PipelineVersionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);

        // billing namespace exists, but the referenced pipeline lives under payments.
        when(namespaceCrudService.findByName(eq("billing")))
                .thenReturn(new Namespace(2L, "billing", "Billing", null, null));
        PipelineDefinitionPo otherNsPipeline = new PipelineDefinitionPo();
        otherNsPipeline.id = 100L;
        otherNsPipeline.namespace = "payments";
        otherNsPipeline.status = "PUBLISHED";
        when(pipelineDefRepo.findById(eq(100L))).thenReturn(Optional.of(otherNsPipeline));

        SubscriptionCrudService service = new SubscriptionCrudService(
                repository, pipelineDefRepo, pipelineVersionRepo, codec, generator, namespaceCrudService);

        Subscription sub = new Subscription(
                null,
                "billing", // subscription namespace
                100L, // pipeline belongs to "payments"
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Subscription.ActionType.RUN,
                null,
                null,
                "billing-rule",
                "desc",
                Subscription.Status.ACTIVE,
                "alice",
                null,
                null);

        assertThatThrownBy(() -> service.create(sub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to namespace")
                .hasMessageContaining("billing");

        // 拒绝发生在 validatePipeline 内，必须未触达写库 / 版本检查。
        verify(repository, never()).save(any());
        verify(pipelineVersionRepo, never()).findById(any());
    }

    @Test
    void createRejectsBlankName() {
        // subscriptions.name NOT NULL（V1__init.sql）；(namespace, name) 唯一索引在 NULL name 上不生效，
        // 必须在应用层挡住空 name，避免产生不可寻址/不可删除的行。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        PipelineVersionRepository pipelineVersionRepo = mock(PipelineVersionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);
        when(namespaceCrudService.findByName(eq("billing")))
                .thenReturn(new Namespace(2L, "billing", "Billing", null, null));

        SubscriptionCrudService service = new SubscriptionCrudService(
                repository, pipelineDefRepo, pipelineVersionRepo, codec, generator, namespaceCrudService);

        Subscription blankName = new Subscription(
                null,
                "billing",
                100L,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Subscription.ActionType.RUN,
                null,
                null,
                "   ",
                "desc",
                Subscription.Status.ACTIVE,
                "alice",
                null,
                null);

        assertThatThrownBy(() -> service.create(blankName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subscription name must not be blank");

        verify(repository, never()).save(any());
        verify(pipelineDefRepo, never()).findById(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void createRejectsUnknownNamespace() {
        // namespace must exist before any pipeline validation runs.
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        PipelineVersionRepository pipelineVersionRepo = mock(PipelineVersionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);
        when(namespaceCrudService.findByName(eq("ghost"))).thenReturn(null);

        SubscriptionCrudService service = new SubscriptionCrudService(
                repository, pipelineDefRepo, pipelineVersionRepo, codec, generator, namespaceCrudService);

        Subscription sub = new Subscription(
                null,
                "ghost",
                100L,
                1,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Subscription.ActionType.RUN,
                null,
                null,
                "ghost-rule",
                "desc",
                Subscription.Status.ACTIVE,
                "alice",
                null,
                null);

        assertThatThrownBy(() -> service.create(sub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace not found")
                .hasMessageContaining("ghost");

        verify(repository, never()).save(any());
        verify(pipelineDefRepo, never()).findById(any());
    }
}
