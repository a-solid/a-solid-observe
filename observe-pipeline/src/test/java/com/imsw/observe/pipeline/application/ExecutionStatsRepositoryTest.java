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
import com.imsw.observe.pipeline.infrastructure.persistence.FailedExecutionPo;
import com.imsw.observe.pipeline.infrastructure.persistence.FailedExecutionRepository;
import com.imsw.observe.pipeline.infrastructure.persistence.TestJpaFactory;

/**
 * B6：验证执行统计 + 成功率（executions 按 {@code started_at}、failed_executions 按 {@code created_at} 分查相除）。
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
    private FailedExecutionRepository failedExecutionRepository;

    @Autowired
    private ExecutionQueryService executionQueryService;

    @BeforeEach
    void setUp() {
        executionRepository.deleteAll();
        failedExecutionRepository.deleteAll();
        Instant base = Instant.parse("2026-07-19T10:00:00Z");
        executionRepository.save(execution("ns", "SUCCESS", base, 1L));
        executionRepository.save(execution("ns", "SUCCESS", base.plusSeconds(60), 1L));
        executionRepository.save(execution("ns", "SHORT_CIRCUITED", base.plusSeconds(120), 2L));
        failedExecutionRepository.save(failed("ns", base.plusSeconds(30), 1L));
    }

    @Test
    void executionStatsComputesByStatusAndSuccessRate() {
        Instant from = Instant.parse("2026-07-19T10:00:00Z");
        Instant to = Instant.parse("2026-07-19T11:00:00Z");

        ExecutionStats stats = executionQueryService.executionStats("ns", from, to, null, null);

        assertThat(stats.total()).isEqualTo(3L);
        assertThat(stats.failedCount()).isEqualTo(1L);
        assertThat(stats.byStatus()).containsEntry("SUCCESS", 2L).containsEntry("SHORT_CIRCUITED", 1L);
        // successRate = total / (total + failed) = 3/4
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

    private static FailedExecutionPo failed(final String ns, final Instant createdAt, final Long pid) {
        FailedExecutionPo po = new FailedExecutionPo();
        po.id = System.nanoTime();
        po.namespace = ns;
        po.pipelineId = pid;
        po.pipelineVersion = 1;
        po.triggerType = "CDC";
        po.errorType = "NODE_EXECUTION";
        po.status = "PENDING";
        po.createdAt = createdAt;
        return po;
    }
}
