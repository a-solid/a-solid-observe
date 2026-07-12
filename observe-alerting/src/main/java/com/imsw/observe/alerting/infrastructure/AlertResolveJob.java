package com.imsw.observe.alerting.infrastructure;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;

import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;

public class AlertResolveJob {

    private static final Logger LOG = LoggerFactory.getLogger(AlertResolveJob.class);

    private static final int BATCH_SIZE = 1000;

    private final AlertRepository alertRepository;

    public AlertResolveJob(final AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    public int resolveExpiredAlerts() {
        Instant now = Instant.now();
        int total = 0;
        while (true) {
            List<String> ids = alertRepository.findExpiredFiringIds(now, PageRequest.of(0, BATCH_SIZE));
            if (ids.isEmpty()) {
                break;
            }
            int updated = alertRepository.resolveBatch(ids, now);
            total += updated;
            if (ids.size() < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            LOG.info("resolved {} expired alerts", total);
        }
        return total;
    }
}
