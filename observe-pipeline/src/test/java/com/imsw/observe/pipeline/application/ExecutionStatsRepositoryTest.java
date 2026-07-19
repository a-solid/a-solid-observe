package com.imsw.observe.pipeline.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import com.imsw.observe.pipeline.infrastructure.persistence.ExecutionPo;
import com.imsw.observe.pipeline.infrastructure.persistence.ExecutionRepository;
import com.imsw.observe.pipeline.infrastructure.persistence.TestJpaFactory;

/**
 * 合表后执行统计 + 成功率（单表 group by status，按 {@code started_at} 同窗口、全量行）。
 *
 * <p>{@code @Import(ExecutionQueryService)} 把 application 包的 service 注册成 bean（TestJpaFactory 只扫 persistence）。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestJpaFactory.class)
@Import(ExecutionQueryService.class)
@Transactional
class ExecutionStatsRepositoryTest {

    @Autowired
    private ExecutionRepository executionRepository;

    @Autowired
    private ExecutionQueryService executionQueryService;

    @BeforeEach
    void setUp() {
        executionRepository.deleteAll();
        Instant base = Instant.parse("2026-07-19T10:00:00Z");
        executionRepository.save(execution("ns", "SUCCESS", base, 1L));
        executionRepository.save(execution("ns", "SUCCESS", base.plusSeconds(60), 1L));
        executionRepository.save(execution("ns", "SHORT_CIRCUITED", base.plusSeconds(120), 2L));
        executionRepository.save(failed("ns", base.plusSeconds(30), 1L));
    }

    @Test
    void executionStatsComputesByStatusAndSuccessRate() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        ExecutionStats stats = executionQueryService.executionStats("ns", from, to, null, null);

        // total 含 FAILED 行（单表全量）；byStatus 含三类
        assertThat(stats.total()).isEqualTo(4L);
        assertThat(stats.failedCount()).isEqualTo(1L);
        assertThat(stats.byStatus())
                .containsEntry("SUCCESS", 2L)
                .containsEntry("SHORT_CIRCUITED", 1L)
                .containsEntry("FAILED", 1L);
        // successRate = (total - failed) / total = 3/4
        assertThat(stats.successRate()).isEqualTo(0.75);
    }

    @Test
    void executionStatsEmptyWindowReturnsHealthyDefault() {
        Instant from = Instant.parse("2030-01-01T00:00:00Z");
        Instant to = Instant.parse("2030-01-02T00:00:00Z");

        ExecutionStats stats = executionQueryService.executionStats("ns", from, to, null, null);

        assertThat(stats.total()).isZero();
        assertThat(stats.failedCount()).isZero();
        // 无执行视为健康
        assertThat(stats.successRate()).isEqualTo(1.0);
    }

    @Test
    void executionStatsIsolatesNamespace() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        ExecutionStats stats = executionQueryService.executionStats("other-ns", from, to, null, null);

        assertThat(stats.total()).isZero();
        assertThat(stats.failedCount()).isZero();
    }

    @Test
    void executionStatsRespectsPipelineFilter() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        ExecutionStats stats = executionQueryService.executionStats("ns", from, to, 2L, null);

        assertThat(stats.total()).isEqualTo(1L);
    }

    private static ExecutionPo execution(
            final String ns, final String status, final Instant startedAt, final Long pid) {
        ExecutionPo po = new ExecutionPo();
        po.id = System.nanoTime();
        po.namespace = ns;
        po.pipelineId = pid;
        po.pipelineVersion = 1;
        po.triggerType = "CDC";
        po.status = status;
        po.startedAt = startedAt;
        po.endedAt = startedAt.plusSeconds(5);
        po.durationMs = 5L;
        po.createdAt = startedAt;
        return po;
    }

    private static ExecutionPo failed(final String ns, final Instant startedAt, final Long pid) {
        ExecutionPo po = new ExecutionPo();
        po.id = System.nanoTime();
        po.namespace = ns;
        po.pipelineId = pid;
        po.pipelineVersion = 1;
        po.triggerType = "CDC";
        po.status = "FAILED";
        po.errorType = "NODE_EXECUTION";
        po.startedAt = startedAt;
        po.endedAt = startedAt.plusSeconds(5);
        po.durationMs = 5L;
        po.createdAt = startedAt;
        return po;
    }
}
