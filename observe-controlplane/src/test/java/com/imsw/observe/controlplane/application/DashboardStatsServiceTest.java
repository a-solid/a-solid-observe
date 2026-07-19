package com.imsw.observe.controlplane.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.imsw.observe.alerting.application.AlertQueryService;
import com.imsw.observe.alerting.application.AlertStats;
import com.imsw.observe.alerting.application.DimensionCount;
import com.imsw.observe.pipeline.application.ExecutionQueryService;
import com.imsw.observe.pipeline.application.ExecutionStats;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.domain.Pipeline;

/**
 * DashboardStatsService 单元测试：验证聚合逻辑、pipelineName lookup fallback、Top-N limit 默认值。
 *
 * <p>QueryService 与 PipelineRegistry 全 mock——验证 service 把它们的输出正确组装到 DashboardStatsDto，
 * 不真实打 repository（JPQL 在 AlertQueryServiceTest 已覆盖）。
 */
class DashboardStatsServiceTest {

    private AlertQueryService alertQueryService;

    private ExecutionQueryService executionQueryService;

    private PipelineRegistry pipelineRegistry;

    private DashboardStatsService service;

    @BeforeEach
    void setUp() {
        alertQueryService = mock(AlertQueryService.class);
        executionQueryService = mock(ExecutionQueryService.class);
        pipelineRegistry = mock(PipelineRegistry.class);
        service = new DashboardStatsService(alertQueryService, executionQueryService, pipelineRegistry);
    }

    @Test
    void aggregateCombinesAlertAndExecutionStatsAndTopN() {
        Instant from = Instant.parse("2026-07-19T00:00:00Z");
        Instant to = Instant.parse("2026-07-19T23:59:59Z");

        when(alertQueryService.alertStats(eq("billing"), eq(from), eq(to), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new AlertStats(
                        "billing",
                        from,
                        to,
                        Map.of("CRITICAL", 3L, "WARNING", 7L, "INFO", 2L),
                        Map.of("ACTIVE", 10L, "EXPIRED", 2L),
                        12L));
        when(executionQueryService.executionStats(eq("billing"), eq(from), eq(to), isNull(), isNull()))
                .thenReturn(new ExecutionStats(
                        "billing",
                        from,
                        to,
                        Map.of("SUCCESS", 100L, "SHORT_CIRCUITED", 5L, "FAILED", 3L),
                        108L,
                        3L,
                        (108 - 3) / 108.0));
        when(alertQueryService.topTeams(eq("billing"), eq(from), eq(to), anyInt()))
                .thenReturn(List.of(new DimensionCount("pay-team", 8L), new DimensionCount("risk-team", 4L)));
        when(executionQueryService.topPipelinesByExecution(eq("billing"), eq(from), eq(to), anyInt()))
                .thenReturn(List.of(
                        new com.imsw.observe.pipeline.application.DimensionCount("101", 50L),
                        new com.imsw.observe.pipeline.application.DimensionCount("102", 30L),
                        new com.imsw.observe.pipeline.application.DimensionCount("999", 5L)));
        when(alertQueryService.topFingerprints(eq("billing"), eq(from), eq(to), anyInt()))
                .thenReturn(List.of(new DimensionCount("fp-a", 6L), new DimensionCount("fp-b", 2L)));

        // pipelineId=101 在 registry 中，102/999 不在（验证 fallback）
        Pipeline p101 = stubPipeline(101L, "order-check");
        PipelineRegistry.Snapshot snap = PipelineRegistry.Snapshot.loaded(Map.of(101L, p101), List.of());
        when(pipelineRegistry.snapshot()).thenReturn(snap);

        var dto = service.aggregate("billing", from, to, 5);

        assertThat(dto.namespace()).isEqualTo("billing");
        assertThat(dto.from()).isEqualTo(from);
        assertThat(dto.to()).isEqualTo(to);
        // alert 聚合
        assertThat(dto.alertsBySeverity()).containsEntry("CRITICAL", 3L).containsEntry("INFO", 2L);
        assertThat(dto.alertsByStatus()).containsEntry("ACTIVE", 10L);
        assertThat(dto.alertsTotal()).isEqualTo(12L);
        assertThat(dto.alertsToday()).isEqualTo(12L); // hero = alert total
        // execution 聚合
        assertThat(dto.executionsByStatus()).containsEntry("SUCCESS", 100L).containsEntry("FAILED", 3L);
        assertThat(dto.executionsTotal()).isEqualTo(108L);
        assertThat(dto.executionsFailed()).isEqualTo(3L);
        assertThat(dto.executionsSuccessRate())
                .isCloseTo((108.0 - 3.0) / 108.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(dto.eventsToday()).isEqualTo(108L); // hero = execution total
        // topN
        assertThat(dto.teamDist()).hasSize(2);
        assertThat(dto.topFingerprints()).hasSize(2);
        // pipelineName lookup：101 命中、102/999 fallback
        assertThat(dto.topPipelines())
                .extracting("pipelineId", "pipelineName", "count")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, "order-check", 50L),
                        org.assertj.core.groups.Tuple.tuple(102L, "<unloaded:102>", 30L),
                        org.assertj.core.groups.Tuple.tuple(999L, "<unloaded:999>", 5L));
    }

    @Test
    void aggregateDefaultsRangeToTodayWhenFromAndToAreNull() {
        // 仅验证 from=null/to=null 走"今天"归一化分支——传任意 stub，断言 from <= now <= to 隐含正确
        when(alertQueryService.alertStats(
                        eq("ns"),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull()))
                .thenReturn(new AlertStats("ns", Instant.now(), Instant.now(), Map.of(), Map.of(), 0L));
        when(executionQueryService.executionStats(
                        eq("ns"),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        isNull(),
                        isNull()))
                .thenReturn(new ExecutionStats("ns", Instant.now(), Instant.now(), Map.of(), 0L, 0L, 1.0));
        when(alertQueryService.topTeams(
                        eq("ns"),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        anyInt()))
                .thenReturn(List.of());
        when(executionQueryService.topPipelinesByExecution(
                        eq("ns"),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        anyInt()))
                .thenReturn(List.of());
        when(alertQueryService.topFingerprints(
                        eq("ns"),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        org.mockito.ArgumentMatchers.any(Instant.class),
                        anyInt()))
                .thenReturn(List.of());

        var dto = service.aggregate("ns", null, null, 5);

        // from = 今天 0:00 UTC；to = now；to >= from；from 在今天
        assertThat(dto.from()).isBeforeOrEqualTo(dto.to());
        assertThat(dto.from().toString()).endsWith("T00:00:00Z"); // 0 点对齐
    }

    @Test
    void aggregateAppliesDefaultLimitWhenNonPositive() {
        Instant from = Instant.parse("2026-07-19T00:00:00Z");
        Instant to = Instant.parse("2026-07-19T23:59:59Z");
        when(alertQueryService.alertStats(eq("ns"), eq(from), eq(to), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(new AlertStats("ns", from, to, Map.of(), Map.of(), 0L));
        when(executionQueryService.executionStats(eq("ns"), eq(from), eq(to), isNull(), isNull()))
                .thenReturn(new ExecutionStats("ns", from, to, Map.of(), 0L, 0L, 1.0));
        when(alertQueryService.topTeams(eq("ns"), eq(from), eq(to), eq(DashboardStatsService.DEFAULT_TOP_LIMIT)))
                .thenReturn(List.of());
        when(executionQueryService.topPipelinesByExecution(
                        eq("ns"), eq(from), eq(to), eq(DashboardStatsService.DEFAULT_TOP_LIMIT)))
                .thenReturn(List.of());
        when(alertQueryService.topFingerprints(eq("ns"), eq(from), eq(to), eq(DashboardStatsService.DEFAULT_TOP_LIMIT)))
                .thenReturn(List.of());

        // limit=0 触发默认值 5
        service.aggregate("ns", from, to, 0);

        org.mockito.Mockito.verify(alertQueryService)
                .topTeams(eq("ns"), eq(from), eq(to), eq(DashboardStatsService.DEFAULT_TOP_LIMIT));
    }

    private static Pipeline stubPipeline(final long id, final String name) {
        return new Pipeline(
                id,
                "billing",
                1,
                Map.of(),
                name,
                Pipeline.Status.PUBLISHED,
                List.of(),
                Instant.now(),
                Instant.now(),
                0.0);
    }
}
