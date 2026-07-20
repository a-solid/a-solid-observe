package com.imsw.observe.controlplane.interfaces;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.imsw.observe.alerting.application.AlertQueryService;
import com.imsw.observe.alerting.application.AlertStats;
import com.imsw.observe.alerting.application.TimeseriesPoint;
import com.imsw.observe.controlplane.application.DashboardStatsService;
import com.imsw.observe.controlplane.interfaces.dto.DashboardStatsDto;
import com.imsw.observe.controlplane.interfaces.web.ErrorResponseException;
import com.imsw.observe.pipeline.application.ExecutionQueryService;
import com.imsw.observe.pipeline.application.ExecutionTimeseriesPoint;

/**
 * StatsController 契约测试：覆盖 alerts / alerts/timeseries / executions / dashboard（B9 新增）四个端点。
 *
 * <p>Controller 只透传到 application service，本测试验证：(1) 参数解析正确、(2) bucket/limit 非法值给
 * ErrorResponseException(BAD_REQUEST)、(3) dashboard 默认区间归一化由 service 承载（controller 只转发 null）。
 */
class StatsControllerTest {

    private AlertQueryService alertQueryService;

    private ExecutionQueryService executionQueryService;

    private DashboardStatsService dashboardStatsService;

    private StatsController controller;

    @BeforeEach
    void setUp() {
        alertQueryService = mock(AlertQueryService.class);
        executionQueryService = mock(ExecutionQueryService.class);
        dashboardStatsService = mock(DashboardStatsService.class);
        controller = new StatsController(alertQueryService, executionQueryService, dashboardStatsService);
    }

    @Test
    void alertStatsDelegatesToServiceWithFilters() {
        Instant from = Instant.parse("2026-07-19T00:00:00Z");
        Instant to = Instant.parse("2026-07-19T23:59:59Z");
        when(alertQueryService.alertStats(
                        eq("billing"), eq(from), eq(to), eq("ACTIVE"), eq("CRITICAL"), eq("pay-team"), eq(101L)))
                .thenReturn(new AlertStats("billing", from, to, Map.of("CRITICAL", 5L), Map.of("ACTIVE", 5L), 5L));

        var resp = controller.alertStats("billing", from, to, "ACTIVE", "CRITICAL", "pay-team", 101L);

        assertThat(resp.data().total()).isEqualTo(5L);
        assertThat(resp.data().bySeverity()).containsEntry("CRITICAL", 5L);
    }

    @Test
    void alertTimeseriesRejectsInvalidBucket() {
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();
        assertThatThrownBy(() -> controller.alertTimeseries("billing", from, to, "5m", null))
                .isInstanceOf(ErrorResponseException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    void alertTimeseriesDelegatesForValidBucket() {
        Instant from = Instant.now().minusSeconds(7200);
        Instant to = Instant.now();
        when(alertQueryService.alertTimeseries(eq("billing"), eq(from), eq(to), eq("1h"), eq(null)))
                .thenReturn(List.of(new TimeseriesPoint(from, 7L, null)));

        var resp = controller.alertTimeseries("billing", from, to, "1h", null);

        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).count()).isEqualTo(7L);
    }

    @Test
    void dashboardRejectsLimitOutOfRange() {
        assertThatThrownBy(() -> controller.dashboard("billing", null, null, 0))
                .isInstanceOf(ErrorResponseException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> controller.dashboard("billing", null, null, 51))
                .isInstanceOf(ErrorResponseException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void dashboardDelegatesToServiceWithDefaultRangeAndLimit() {
        Instant from = Instant.parse("2026-07-19T00:00:00Z");
        Instant to = Instant.parse("2026-07-19T23:59:59Z");
        DashboardStatsDto stub = new DashboardStatsDto(
                "billing",
                from,
                to,
                Map.of("CRITICAL", 3L),
                Map.of("ACTIVE", 5L),
                8L,
                Map.of("SUCCESS", 100L),
                105L,
                5L,
                0.95,
                105L,
                8L,
                List.of(new DashboardStatsDto.DimensionCountDto("pay-team", 5L)),
                List.of(new DashboardStatsDto.PipelineCountDto(101L, "order-check", 50L)),
                List.of(new DashboardStatsDto.DimensionCountDto("fp-a", 4L)));
        when(dashboardStatsService.aggregate(eq("billing"), eq(null), eq(null), eq(5)))
                .thenReturn(stub);

        var resp = controller.dashboard("billing", null, null, 5);

        assertThat(resp.data().alertsTotal()).isEqualTo(8L);
        assertThat(resp.data().topPipelines()).hasSize(1);
        verify(dashboardStatsService).aggregate(eq("billing"), eq(null), eq(null), eq(5));
    }

    @Test
    void executionTimeseriesRejectsInvalidBucket() {
        Instant from = Instant.now().minusSeconds(3600);
        Instant to = Instant.now();
        assertThatThrownBy(() -> controller.executionTimeseries("billing", from, to, "5m", null, null))
                .isInstanceOf(ErrorResponseException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    void executionTimeseriesDelegatesForValidBucket() {
        Instant from = Instant.now().minusSeconds(7200);
        Instant to = Instant.now();
        when(executionQueryService.executionTimeseries(eq("billing"), eq(from), eq(to), eq("1h"), eq(null), eq(null)))
                .thenReturn(List.of(
                        new ExecutionTimeseriesPoint(from, 88L, "SUCCESS"),
                        new ExecutionTimeseriesPoint(from, 2L, "FAILED")));

        var resp = controller.executionTimeseries("billing", from, to, "1h", null, null);

        assertThat(resp.data()).hasSize(2);
        assertThat(resp.data().get(0).status()).isEqualTo("SUCCESS");
        assertThat(resp.data().get(0).count()).isEqualTo(88L);
        assertThat(resp.data().get(1).status()).isEqualTo("FAILED");
        assertThat(resp.data().get(1).count()).isEqualTo(2L);
    }

    @Test
    void executionTimeseriesPassesFilters() {
        Instant from = Instant.now().minusSeconds(7200);
        Instant to = Instant.now();
        when(executionQueryService.executionTimeseries(eq("billing"), eq(from), eq(to), eq("1d"), eq(101L), eq("CRON")))
                .thenReturn(List.of());

        var resp = controller.executionTimeseries("billing", from, to, "1d", 101L, "CRON");

        assertThat(resp.data()).isEmpty();
        verify(executionQueryService)
                .executionTimeseries(eq("billing"), eq(from), eq(to), eq("1d"), eq(101L), eq("CRON"));
    }
}
