package com.imsw.observe.alerting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import com.imsw.observe.alerting.TestJpaFactory;
import com.imsw.observe.alerting.domain.AlertStatus;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertPo;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.kernel.alert.model.Severity;

/**
 * B6：验证告警聚合统计 JPQL 在 H2 上正确（{@code EXTRACT}/{@code GROUP BY} 可移植性锁定）。
 *
 * <p>覆盖：bySeverity/byStatus 计数、可选过滤、时间窗口半开 {@code [from, to)}、时间序列缺桶补零（service 层）。
 *
 * <p>{@code @Import(AlertQueryService)} 把 application 包的 service 注册成 bean（TestJpaFactory 只扫 persistence）。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestJpaFactory.class)
@Import(AlertQueryService.class)
@Transactional
class AlertStatsRepositoryTest {

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AlertQueryService alertQueryService;

    @BeforeEach
    void setUp() {
        alertRepository.deleteAll();
        // 灌入测试数据：窗口内 3 条（2 CRITICAL FIRING, 1 WARNING RESOLVED）+ 窗口外 1 条
        // B9 / ADR-0004：team 维度从一等列下线为 label_team 投影；测试数据在 labelTeam 上携带 team 值。
        Instant base = Instant.parse("2026-07-19T10:00:00Z");
        alertRepository.save(alert("ns", "team-a", Severity.CRITICAL, AlertStatus.FIRING, base, 1L));
        alertRepository.save(alert("ns", "team-a", Severity.CRITICAL, AlertStatus.FIRING, base.plusSeconds(900), 1L));
        alertRepository.save(alert("ns", "team-b", Severity.WARNING, AlertStatus.RESOLVED, base.plusSeconds(1800), 2L));
        // 窗口外（早于 from）
        alertRepository.save(alert("ns", "team-a", Severity.CRITICAL, AlertStatus.FIRING, base.minusSeconds(3600), 1L));
    }

    @Test
    void alertStatsCountsBySeverityAndStatus() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        AlertStats stats = alertQueryService.alertStats("ns", from, to, null, null, null, null);

        assertThat(stats.total()).isEqualTo(3L);
        assertThat(stats.bySeverity()).containsEntry("CRITICAL", 2L).containsEntry("WARNING", 1L);
        assertThat(stats.byStatus()).containsEntry("FIRING", 2L).containsEntry("RESOLVED", 1L);
    }

    @Test
    void alertStatsRespectsSeverityFilter() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        AlertStats stats = alertQueryService.alertStats("ns", from, to, null, "CRITICAL", null, null);

        assertThat(stats.total()).isEqualTo(2L);
        assertThat(stats.bySeverity()).containsEntry("CRITICAL", 2L).doesNotContainKey("WARNING");
    }

    @Test
    void alertStatsRespectsTeamFilter() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        AlertStats stats = alertQueryService.alertStats("ns", from, to, null, null, "team-b", null);

        assertThat(stats.total()).isEqualTo(1L);
    }

    @Test
    void alertStatsEmptyWindowReturnsZeroTotal() {
        Instant from = Instant.parse("2030-01-01T00:00:00Z");
        Instant to = Instant.parse("2030-01-02T00:00:00Z");

        AlertStats stats = alertQueryService.alertStats("ns", from, to, null, null, null, null);

        assertThat(stats.total()).isZero();
        assertThat(stats.bySeverity()).isEmpty();
    }

    @Test
    void alertStatsIsolatesNamespace() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        AlertStats stats = alertQueryService.alertStats("other-ns", from, to, null, null, null, null);

        assertThat(stats.total()).isZero();
    }

    @Test
    void timeseriesHourlyFillsZeroBuckets() {
        // 窗口 3 小时，数据在第 1 小时内（10:00-10:30）→ 11:00、12:00 桶应为 0
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T13:00:00Z");

        List<TimeseriesPoint> points = alertQueryService.alertTimeseries("ns", from, to, "1h", null);

        assertThat(points).hasSize(3);
        assertThat(points.get(0).bucketStart()).isEqualTo(Instant.parse("2026-07-19T10:00:00Z"));
        assertThat(points.get(0).count()).isEqualTo(3L);
        assertThat(points.get(1).bucketStart()).isEqualTo(Instant.parse("2026-07-19T11:00:00Z"));
        assertThat(points.get(1).count()).isZero();
        assertThat(points.get(2).count()).isZero();
    }

    @Test
    void timeseriesDailyBucketsByDay() {
        // 日桶窗口 07-19 00:00 → 07-21 00:00：07-19 当日 4 条（含 09:00 那条「窗口外」对小时窗而言、但对日窗在内）
        Instant from = Instant.parse("2026-07-19T00:00:00Z");
        Instant to = Instant.parse("2026-07-21T00:00:00Z");

        List<TimeseriesPoint> points = alertQueryService.alertTimeseries("ns", from, to, "1d", null);

        assertThat(points).hasSize(2);
        assertThat(points.get(0).bucketStart()).isEqualTo(Instant.parse("2026-07-19T00:00:00Z"));
        assertThat(points.get(0).count()).isEqualTo(4L);
        assertThat(points.get(1).count()).isZero();
    }

    private static AlertPo alert(
            final String namespace,
            final String team,
            final Severity severity,
            final AlertStatus status,
            final Instant startsAt,
            final Long pipelineId) {
        AlertPo po = new AlertPo();
        po.id = System.nanoTime();
        po.namespace = namespace;
        po.labelTeam = team; // B9 / ADR-0004：team 改为 label_team 投影列
        po.pipelineId = pipelineId;
        po.pipelineVersion = 1;
        po.executionId = 1L;
        po.fingerprint = "fp-" + po.id;
        po.severity = severity.name();
        po.labels = java.util.Map.of();
        po.annotations = java.util.Map.of();
        po.startsAt = startsAt;
        po.lastSeenAt = startsAt;
        po.endsAt = startsAt.plusSeconds(600);
        po.resolvedAt = status == AlertStatus.RESOLVED ? startsAt.plusSeconds(600) : null;
        po.status = status.name();
        po.dedupCount = 1;
        po.createdAt = startsAt;
        po.updatedAt = startsAt;
        return po;
    }
}
