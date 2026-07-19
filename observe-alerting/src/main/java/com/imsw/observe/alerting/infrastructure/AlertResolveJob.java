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
 * ADR-0005 §1：wave 过期自动 resolve。{@code @Scheduled} 周期扫 {@code status=ACTIVE and ends_at<now} 批量翻 EXPIRED。
 *
 * <p>两维分离后：只看系统态（status），<b>不看 disposition</b>——ACKNOWLEDGED/IGNORED 的 ACTIVE 告警到期照常
 * 翻 EXPIRED（用户处置不阻止系统到期）。
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
        expireAlerts();
    }

    /** 测试可直调：扫过期 ACTIVE，批量翻 EXPIRED，返回翻转条数。 */
    public int expireAlerts() {
        Instant now = Instant.now();
        int total = 0;
        while (true) {
            List<Long> ids = alertRepository.findExpiredActiveIds(now, PageRequest.of(0, batchSize));
            if (ids.isEmpty()) {
                break;
            }
            int updated = alertRepository.expireBatch(ids, now);
            total += updated;
            if (ids.size() < batchSize) {
                break;
            }
        }
        if (total > 0) {
            LOG.info("expired {} active alerts", total);
        }
        return total;
    }
}
