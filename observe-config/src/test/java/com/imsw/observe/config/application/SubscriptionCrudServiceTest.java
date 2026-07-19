package com.imsw.observe.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.imsw.observe.config.domain.Namespace;
import com.imsw.observe.config.domain.SubscriptionDefinition;
import com.imsw.observe.config.infrastructure.ConditionCodec;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionRepository;
import com.imsw.observe.config.infrastructure.persistence.SubscriptionRepository;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

class SubscriptionCrudServiceTest {

    @Test
    void createRejectsSubscriptionWhoseNamespaceDiffersFromPipeline() {
        // 软隔离铁律（ADR-0002）：subscription 只能引用同 namespace 下的 pipeline。
        // validatePipeline 必须在 def.namespace != subscription.namespace 时拒绝，
        // 即便 subscription 自身的 namespace 在 namespaces 表里存在。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
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

        SubscriptionCrudService service =
                new SubscriptionCrudService(repository, pipelineDefRepo, codec, generator, namespaceCrudService);

        SubscriptionDefinition sub = new SubscriptionDefinition(
                null,
                "billing", // subscription namespace
                List.of(100L), // pipeline belongs to "payments"
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                SubscriptionDefinition.ActionType.RUN,
                null,
                null,
                "billing-rule",
                "desc",
                SubscriptionDefinition.Status.ACTIVE,
                "alice",
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.create(sub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to namespace")
                .hasMessageContaining("billing");

        // 拒绝发生在 validatePipeline 内，必须未触达写库。
        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsBlankName() {
        // subscriptions.name NOT NULL（V1__init.sql）；(namespace, name) 唯一索引在 NULL name 上不生效，
        // 必须在应用层挡住空 name，避免产生不可寻址/不可删除的行。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);
        when(namespaceCrudService.findByName(eq("billing")))
                .thenReturn(new Namespace(2L, "billing", "Billing", null, null));

        SubscriptionCrudService service =
                new SubscriptionCrudService(repository, pipelineDefRepo, codec, generator, namespaceCrudService);

        SubscriptionDefinition blankName = new SubscriptionDefinition(
                null,
                "billing",
                List.of(100L),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                SubscriptionDefinition.ActionType.RUN,
                null,
                null,
                "   ",
                "desc",
                SubscriptionDefinition.Status.ACTIVE,
                "alice",
                null,
                null,
                null,
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
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);
        when(namespaceCrudService.findByName(eq("ghost"))).thenReturn(null);

        SubscriptionCrudService service =
                new SubscriptionCrudService(repository, pipelineDefRepo, codec, generator, namespaceCrudService);

        SubscriptionDefinition sub = new SubscriptionDefinition(
                null,
                "ghost",
                List.of(100L),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                SubscriptionDefinition.ActionType.RUN,
                null,
                null,
                "ghost-rule",
                "desc",
                SubscriptionDefinition.Status.ACTIVE,
                "alice",
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.create(sub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace not found")
                .hasMessageContaining("ghost");

        verify(repository, never()).save(any());
        verify(pipelineDefRepo, never()).findById(any());
    }

    @Test
    void createRejectsEmptyPipelineIds() {
        // 扇出：pipelineIds 必须非空（>=1）。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);
        when(namespaceCrudService.findByName(eq("billing")))
                .thenReturn(new Namespace(2L, "billing", "Billing", null, null));

        SubscriptionCrudService service =
                new SubscriptionCrudService(repository, pipelineDefRepo, codec, generator, namespaceCrudService);

        SubscriptionDefinition sub = new SubscriptionDefinition(
                null,
                "billing",
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                SubscriptionDefinition.ActionType.RUN,
                null,
                null,
                "empty-pipelines",
                "desc",
                SubscriptionDefinition.Status.ACTIVE,
                "alice",
                null,
                null,
                null,
                null,
                null);

        assertThatThrownBy(() -> service.create(sub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipelineIds must not be empty");

        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsCronSubscriptionWithoutCronExpression() {
        // B4：sourceType==CRON 时 cronExpression 必填（ADR-0007）。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);

        stubValidPipeline(namespaceCrudService, pipelineDefRepo, "billing", 100L);
        SubscriptionCrudService service =
                new SubscriptionCrudService(repository, pipelineDefRepo, codec, generator, namespaceCrudService);

        SubscriptionDefinition sub = cronSubscription("billing", 100L, "billing-rule", null);

        assertThatThrownBy(() -> service.create(sub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cronExpression must not be blank");

        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsCronSubscriptionWithUnparseableCronExpression() {
        // B4：cronExpression 必须可被 Spring CronExpression 解析；解析失败转译为清晰错误。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);

        stubValidPipeline(namespaceCrudService, pipelineDefRepo, "billing", 100L);
        SubscriptionCrudService service =
                new SubscriptionCrudService(repository, pipelineDefRepo, codec, generator, namespaceCrudService);

        SubscriptionDefinition sub = cronSubscription("billing", 100L, "billing-rule", "not-a-valid-cron-expr-999");

        assertThatThrownBy(() -> service.create(sub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid cronExpression")
                .hasMessageContaining("not-a-valid-cron-expr-999");

        verify(repository, never()).save(any());
    }

    @Test
    void createAcceptsCronSubscriptionWithValidCronExpression() {
        // B4：合法 cronExpression（6 字段 Spring CronExpression）通过校验并落库。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);

        stubValidPipeline(namespaceCrudService, pipelineDefRepo, "billing", 100L);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SubscriptionCrudService service =
                new SubscriptionCrudService(repository, pipelineDefRepo, codec, generator, namespaceCrudService);

        SubscriptionDefinition sub = cronSubscription("billing", 100L, "billing-rule", "0 */5 * * * *");

        SubscriptionDefinition saved = service.create(sub);
        assertThat(saved.cronExpression()).isEqualTo("0 */5 * * * *");
        verify(repository).save(any());
    }

    @Test
    void createRejectsNonCronSubscriptionCarryingCronExpression() {
        // B4：sourceType != CRON 时 cronExpression 必须为 null，避免 CRON 配置泄漏到 CDC/API 订阅。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);

        stubValidPipeline(namespaceCrudService, pipelineDefRepo, "billing", 100L);
        SubscriptionCrudService service =
                new SubscriptionCrudService(repository, pipelineDefRepo, codec, generator, namespaceCrudService);

        SubscriptionDefinition sub = new SubscriptionDefinition(
                null,
                "billing",
                List.of(100L),
                null,
                null,
                null,
                null,
                null,
                null,
                SourceType.CDC,
                null,
                SubscriptionDefinition.ActionType.RUN,
                null,
                null,
                "cdc-with-cron",
                "desc",
                SubscriptionDefinition.Status.ACTIVE,
                "alice",
                null,
                null,
                "0 */5 * * * *", // 不该出现在 CDC 订阅上
                null,
                null);

        assertThatThrownBy(() -> service.create(sub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cronExpression must be null")
                .hasMessageContaining("CDC");

        verify(repository, never()).save(any());
    }

    @Test
    void createRejectsCronSubscriptionWhenMqDivergesFromCronName() {
        // B4-T3 review Finding #2：CRON 订阅的 mq（Snapshot 索引键 / TickMeta.source 来源）必须等于
        // cronName（或 cronName == null）。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);

        stubValidPipeline(namespaceCrudService, pipelineDefRepo, "billing", 100L);
        SubscriptionCrudService service =
                new SubscriptionCrudService(repository, pipelineDefRepo, codec, generator, namespaceCrudService);

        SubscriptionDefinition sub = new SubscriptionDefinition(
                null,
                "billing",
                List.of(100L),
                "idx-key", // mq（索引键）
                null,
                null,
                null,
                null,
                null,
                SourceType.CRON,
                null,
                SubscriptionDefinition.ActionType.RUN,
                null,
                null,
                "billing-rule",
                "desc",
                SubscriptionDefinition.Status.ACTIVE,
                "alice",
                null,
                null,
                "0 */5 * * * *",
                "logical-name", // cronName != mq → 拒绝
                null);

        assertThatThrownBy(() -> service.create(sub))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CRON subscription mq and cronName must match")
                .hasMessageContaining("mq=idx-key")
                .hasMessageContaining("cronName=logical-name");

        verify(repository, never()).save(any());
    }

    @Test
    void createAcceptsCronSubscriptionWhenCronNameIsNull() {
        // B4-T3 review Finding #2 的允许路径：cronName == null 时合法（mq 作为规范名）。
        SubscriptionRepository repository = mock(SubscriptionRepository.class);
        PipelineDefinitionRepository pipelineDefRepo = mock(PipelineDefinitionRepository.class);
        ConditionCodec codec = mock(ConditionCodec.class);
        NamespaceCrudService namespaceCrudService = mock(NamespaceCrudService.class);
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);

        stubValidPipeline(namespaceCrudService, pipelineDefRepo, "billing", 100L);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        SubscriptionCrudService service =
                new SubscriptionCrudService(repository, pipelineDefRepo, codec, generator, namespaceCrudService);

        SubscriptionDefinition sub = cronSubscription("billing", 100L, "billing-rule", "0 */5 * * * *");

        SubscriptionDefinition saved = service.create(sub);
        assertThat(saved.cronExpression()).isEqualTo("0 */5 * * * *");
        verify(repository).save(any());
    }

    /** 构造一条合法结构（除 cronExpression 外）的 CRON 订阅。 */
    private static SubscriptionDefinition cronSubscription(
            final String namespace, final Long pipelineId, final String name, final String cronExpression) {
        return new SubscriptionDefinition(
                null,
                namespace,
                List.of(pipelineId),
                "cron-source",
                null,
                null,
                null,
                null,
                null,
                SourceType.CRON,
                null,
                SubscriptionDefinition.ActionType.RUN,
                null,
                null,
                name,
                "desc",
                SubscriptionDefinition.Status.ACTIVE,
                "alice",
                null,
                null,
                cronExpression,
                null,
                null);
    }

    /** 桩一个 namespace 下 PUBLISHED 的 pipeline，使 validatePipeline 通过。 */
    private static void stubValidPipeline(
            final NamespaceCrudService namespaceCrudService,
            final PipelineDefinitionRepository pipelineDefRepo,
            final String namespace,
            final Long pipelineId) {
        when(namespaceCrudService.findByName(eq(namespace)))
                .thenReturn(new Namespace(2L, namespace, namespace, null, null));
        PipelineDefinitionPo def = new PipelineDefinitionPo();
        def.id = pipelineId;
        def.namespace = namespace;
        def.status = "PUBLISHED";
        when(pipelineDefRepo.findById(eq(pipelineId))).thenReturn(Optional.of(def));
    }
}
