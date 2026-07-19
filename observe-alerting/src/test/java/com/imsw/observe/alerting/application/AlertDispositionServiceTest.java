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
import com.imsw.observe.alerting.domain.AlertDisposition;
import com.imsw.observe.alerting.domain.AlertEntity;
import com.imsw.observe.alerting.domain.AlertStatus;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertPo;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.kernel.alert.model.Severity;
import com.imsw.observe.kernel.error.ResourceNotFoundException;

/**
 * 告警处置（ADR-0005 两维分离后）：ack / ignore 改 disposition 列，与 status 正交。
 *
 * <p>覆盖：ack/ignore 落 disposition；任意 status（ACTIVE/EXPIRED）都能处置；不存在 404；跨 namespace 404。
 * 用户 resolve 已砍（R2），不再测。
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
        alertId = insertAlert("ns", "ACTIVE", "NONE", null);
    }

    @Test
    void acknowledgeStampsDisposition() {
        AlertEntity acked = dispositionService.acknowledge("ns", alertId, "looking", "ops");

        assertThat(acked.disposition()).isEqualTo(AlertDisposition.ACKNOWLEDGED);
        assertThat(acked.status()).isEqualTo(AlertStatus.ACTIVE); // status 不变（正交）
        assertThat(acked.ackBy()).isEqualTo("ops");
        assertThat(acked.ackNote()).isEqualTo("looking");
        assertThat(acked.ackAt()).isNotNull();
    }

    @Test
    void ignoreStampsDisposition() {
        AlertEntity ignored = dispositionService.ignore("ns", alertId, "dup", "ops");
        assertThat(ignored.disposition()).isEqualTo(AlertDisposition.IGNORED);
        assertThat(ignored.status()).isEqualTo(AlertStatus.ACTIVE);
    }

    @Test
    void acknowledgeExpiredAlertAllowed() {
        // 维度正交：EXPIRED 行也能 ack（事后标记）。本项目 EXPIRED 非条件恢复，事后 ack 有意义。
        Long expiredId = insertAlert("ns", "EXPIRED", "NONE", Instant.now());
        AlertEntity acked = dispositionService.acknowledge("ns", expiredId, "saw it late", "ops");

        assertThat(acked.disposition()).isEqualTo(AlertDisposition.ACKNOWLEDGED);
        assertThat(acked.status()).isEqualTo(AlertStatus.EXPIRED); // status 不被 ack 改
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

    private Long insertAlert(
            final String namespace, final String status, final String disposition, final Instant resolvedAt) {
        AlertPo po = new AlertPo();
        po.id = System.nanoTime();
        po.namespace = namespace;
        po.labelTeam = "team-a"; // B9 / ADR-0004：team 改为 label_team 投影列
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
        po.disposition = disposition;
        po.dedupCount = 1;
        po.createdAt = now;
        po.updatedAt = now;
        return alertRepository.save(po).id;
    }
}
