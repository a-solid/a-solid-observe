package com.imsw.observe.alerting.infrastructure;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;

import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;

/**
 * ADR-0005 §1：wave 过期自动 resolve。{@code @Scheduled} 周期扫 {@code status=FIRING and ends_at<now} 批量翻 RESOLVED。
 *
 * <p>间隔由 {@code observe.alerting.resolve-job.interval-millis}（默认 60s）控制；{@code @EnableScheduling} 已在
 * {@code ObserveApplication} 开启。
 */
public class AlertResolveJob {

    private static final Logger LOG = LoggerFactory.getLogger(AlertResolveJob.class);

    private final int batchSize;

    private final AlertRepository alertRepository;

    public AlertResolveJob(
            final AlertRepository alertRepository,
            @Value("${observe.alerting.resolve-job.batch-size:1000}") final int batchSize) {
        this.alertRepository = alertRepository;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${observe.alerting.resolve-job.interval-millis:60000}")
    public void scheduled() {
        resolveExpiredAlerts();
    }

    /** 测试可直调：扫过期 FIRING，批量翻 RESOLVED，返回翻转条数。 */
    public int resolveExpiredAlerts() {
        Instant now = Instant.now();
        int total = 0;
        while (true) {
            List<Long> ids = alertRepository.findExpiredFiringIds(now, PageRequest.of(0, batchSize));
            if (ids.isEmpty()) {
                break;
            }
            int updated = alertRepository.resolveBatch(ids, now);
            total += updated;
            if (ids.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            LOG.info("resolved {} expired alerts", total);
        }
        return total;
    }
}
