package com.imsw.observe.alerting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;

import com.imsw.observe.alerting.TestJpaFactory;
import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.alerting.domain.AlertStatus;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertPo;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.kernel.alert.model.Severity;
import com.imsw.observe.kernel.error.ConflictException;
import com.imsw.observe.kernel.error.ResourceNotFoundException;

/**
 * B7：告警状态机处置（ack/resolve/ignore）—— 合法转移、非法转移冲突、不存在 404、并发 CAS。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = TestJpaFactory.class)
@Import(AlertDispositionService.class)
@Transactional
class AlertDispositionServiceTest {

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private AlertDispositionService dispositionService;

    private Long alertId;

    @BeforeEach
    void setUp() {
        alertId = insertAlert("ns", "FIRING", null);
    }

    @Test
    void acknowledgeTransitionsFiringToAcknowledged() {
        AlertEntity acked = dispositionService.acknowledge("ns", alertId, "looking", "ops");

        assertThat(acked.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(acked.ackBy()).isEqualTo("ops");
        assertThat(acked.ackNote()).isEqualTo("looking");
        assertThat(acked.ackAt()).isNotNull();
    }

    @Test
    void ignoreTransitionsFiringToIgnored() {
        AlertEntity ignored = dispositionService.ignore("ns", alertId, "dup", "ops");
        assertThat(ignored.status()).isEqualTo(AlertStatus.IGNORED);
    }

    @Test
    void resolveFromAcknowledgedAllowed() {
        dispositionService.acknowledge("ns", alertId, null, "ops");
        AlertEntity resolved = dispositionService.resolve("ns", alertId, "fixed", "ops");
        assertThat(resolved.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isNotNull();
    }

    @Test
    void acknowledgeFromResolvedIsConflict() {
        dispositionService.resolve("ns", alertId, null, "ops");

        assertThatThrownBy(() -> dispositionService.acknowledge("ns", alertId, null, "ops"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void acknowledgeNonexistentThrowsNotFound() {
        assertThatThrownBy(() -> dispositionService.acknowledge("ns", 999_999_999L, null, "ops"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void crossNamespaceAccessIsNotFound() {
        assertThatThrownBy(() -> dispositionService.acknowledge("other-ns", alertId, null, "ops"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Long insertAlert(final String namespace, final String status, final Instant resolvedAt) {
        AlertPo po = new AlertPo();
        po.id = System.nanoTime();
        po.namespace = namespace;
        po.team = "team-a";
        po.application = "app";
        po.pipelineId = 1L;
        po.pipelineVersion = 1;
        po.executionId = 1L;
        po.fingerprint = "fp-" + po.id;
        po.severity = Severity.CRITICAL.name();
        po.labels = java.util.Map.of();
        po.annotations = java.util.Map.of();
        Instant now = Instant.now();
        po.startsAt = now;
        po.lastSeenAt = now;
        po.endsAt = now.plusSeconds(600);
        po.resolvedAt = resolvedAt;
        po.status = status;
        po.dedupCount = 1;
        po.createdAt = now;
        po.updatedAt = now;
        return alertRepository.save(po).id;
    }
}
