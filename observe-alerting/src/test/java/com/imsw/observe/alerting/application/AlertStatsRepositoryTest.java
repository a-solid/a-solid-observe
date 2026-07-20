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
        // 灌入测试数据：窗口内 3 条（2 CRITICAL ACTIVE, 1 WARNING EXPIRED）+ 窗口外 1 条
        // B9 / ADR-0004：team 维度从一等列下线为 label_team 投影；测试数据在 labelTeam 上携带 team 值。
        Instant base = Instant.parse("2026-07-19T10:00:00Z");
        alertRepository.save(alert("ns", "team-a", Severity.CRITICAL, AlertStatus.ACTIVE, base, 1L));
        alertRepository.save(alert("ns", "team-a", Severity.CRITICAL, AlertStatus.ACTIVE, base.plusSeconds(900), 1L));
        alertRepository.save(alert("ns", "team-b", Severity.WARNING, AlertStatus.EXPIRED, base.plusSeconds(1800), 2L));
        // 窗口外（早于 from）
        alertRepository.save(alert("ns", "team-a", Severity.CRITICAL, AlertStatus.ACTIVE, base.minusSeconds(3600), 1L));
    }

    @Test
    void alertStatsCountsBySeverityAndStatus() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        AlertStats stats = alertQueryService.alertStats("ns", from, to, null, null, null, null);

        assertThat(stats.total()).isEqualTo(3L);
        assertThat(stats.bySeverity()).containsEntry("CRITICAL", 2L).containsEntry("WARNING", 1L);
        assertThat(stats.byStatus()).containsEntry("ACTIVE", 2L).containsEntry("EXPIRED", 1L);
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
        // 数据含 CRITICAL(2) + WARNING(1) 两个级别 → 每个桶每个级别一行
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T13:00:00Z");

        List<TimeseriesPoint> points = alertQueryService.alertTimeseries("ns", from, to, "1h", null);

        assertThat(points).hasSize(9);
        // 10:00 桶
        assertThat(points.get(0))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T10:00:00Z"), 2L, "CRITICAL"));
        assertThat(points.get(1))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T10:00:00Z"), 1L, "WARNING"));
        assertThat(points.get(2))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T10:00:00Z"), 0L, "INFO"));
        // 11:00 桶补零
        assertThat(points.get(3))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T11:00:00Z"), 0L, "CRITICAL"));
        assertThat(points.get(4))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T11:00:00Z"), 0L, "WARNING"));
        assertThat(points.get(5))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T11:00:00Z"), 0L, "INFO"));
        // 12:00 桶补零
        assertThat(points.get(6))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T12:00:00Z"), 0L, "CRITICAL"));
        assertThat(points.get(7))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T12:00:00Z"), 0L, "WARNING"));
        assertThat(points.get(8))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T12:00:00Z"), 0L, "INFO"));
    }

    @Test
    void timeseriesDailyBucketsByDay() {
        // 日桶窗口 07-19 00:00 → 07-21 00:00：07-19 当日 4 条（含 09:00 那条「窗口外」对小时窗而言、但对日窗在内）
        // 数据含 CRITICAL(3) + WARNING(1) 两个级别 → 每个桶每个级别一行
        Instant from = Instant.parse("2026-07-19T00:00:00Z");
        Instant to = Instant.parse("2026-07-21T00:00:00Z");

        List<TimeseriesPoint> points = alertQueryService.alertTimeseries("ns", from, to, "1d", null);

        assertThat(points).hasSize(6);
        // 07-19 桶
        assertThat(points.get(0))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T00:00:00Z"), 3L, "CRITICAL"));
        assertThat(points.get(1))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T00:00:00Z"), 1L, "WARNING"));
        assertThat(points.get(2))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-19T00:00:00Z"), 0L, "INFO"));
        // 07-20 桶补零
        assertThat(points.get(3))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-20T00:00:00Z"), 0L, "CRITICAL"));
        assertThat(points.get(4))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-20T00:00:00Z"), 0L, "WARNING"));
        assertThat(points.get(5))
                .usingRecursiveComparison()
                .isEqualTo(new TimeseriesPoint(Instant.parse("2026-07-20T00:00:00Z"), 0L, "INFO"));
    }

    // ---------- B9 dashboard Top-N 聚合 ----------
    // 复用 @BeforeEach 的 setUp 数据（窗口内 3 条：team-a CRITICAL ACTIVE x2 + team-b WARNING EXPIRED x1，
    // 各自 fingerprint 不同；窗口外 1 条 team-a CRITICAL ACTIVE）。新增 1 条相同 fingerprint 的 alert 验证聚合。

    @Test
    void topFingerprintsAggregatesAndOrdersByCountDesc() {
        // setUp 数据：窗口内 3 条不同 fingerprint（各 1 条）。再加 1 条与 setUp 第一条同 fp 的 alert → 该 fp 应为 2 条居首。
        Instant base = Instant.parse("2026-07-19T10:00:00Z");
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");
        // 抓 setUp 第一条 alert 的 fingerprint，再加 1 条同 fp（窗口内）
        AlertPo first = alertRepository.findAll().stream()
                .filter(a -> "ns".equals(a.namespace) && a.startsAt.equals(base))
                .findFirst()
                .orElseThrow();
        alertRepository.save(alertWithFp(
                "ns", "team-a", Severity.CRITICAL, AlertStatus.ACTIVE, base.plusSeconds(60), 1L, first.fingerprint));

        List<DimensionCount> top = alertQueryService.topFingerprints("ns", from, to, 5);

        // setUp 3 条（fp 各不同）+ 新增 1 条与 first.fingerprint 相同 → first.fp 计数 2，其余 2 条各 1
        assertThat(top).hasSize(3);
        assertThat(top.get(0).count()).isEqualTo(2L); // 排首位
        assertThat(top.get(0).dimension()).isEqualTo(first.fingerprint);
        // 剩余 2 条各 1（按 count desc + fingerprint asc 二级排序）
        assertThat(top.get(1).count()).isEqualTo(1L);
        assertThat(top.get(2).count()).isEqualTo(1L);
    }

    @Test
    void topTeamsAggregatesByLabelTeam() {
        // setUp 数据：team-a 2 条、team-b 1 条（都在窗口内）
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        List<DimensionCount> top = alertQueryService.topTeams("ns", from, to, 5);

        // team-a 2 条居首（按 count desc），team-b 1 条
        assertThat(top)
                .extracting("dimension", "count")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("team-a", 2L),
                        org.assertj.core.groups.Tuple.tuple("team-b", 1L));
    }

    @Test
    void topNAggregatesRespectNamespaceIsolation() {
        // setUp 数据全在 "ns"。查 "other-ns" 应返回空。
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        List<DimensionCount> top = alertQueryService.topFingerprints("other-ns", from, to, 5);
        assertThat(top).isEmpty();
    }

    private static AlertPo alertWithFp(
            final String namespace,
            final String team,
            final Severity severity,
            final AlertStatus status,
            final Instant startsAt,
            final Long pipelineId,
            final String fingerprint) {
        AlertPo po = new AlertPo();
        po.id = System.nanoTime();
        po.namespace = namespace;
        po.labelTeam = team;
        po.pipelineId = pipelineId;
        po.pipelineVersion = 1;
        po.executionId = 1L;
        po.fingerprint = fingerprint;
        po.severity = severity.name();
        po.labels = java.util.Map.of();
        po.annotations = java.util.Map.of();
        po.startsAt = startsAt;
        po.lastSeenAt = startsAt;
        po.endsAt = startsAt.plusSeconds(600);
        po.resolvedAt = status == AlertStatus.EXPIRED ? startsAt.plusSeconds(600) : null;
        po.status = status.name();
        po.disposition = "NONE";
        po.dedupCount = 1;
        po.createdAt = startsAt;
        po.updatedAt = startsAt;
        return po;
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
        po.resolvedAt = status == AlertStatus.EXPIRED ? startsAt.plusSeconds(600) : null;
        po.status = status.name();
        po.disposition = "NONE";
        po.dedupCount = 1;
        po.createdAt = startsAt;
        po.updatedAt = startsAt;
        return po;
    }
}
