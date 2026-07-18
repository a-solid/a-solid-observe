package com.imsw.observe.alerting.infrastructure.persistence.silence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AlertSilenceRepository extends JpaRepository<AlertSilencePo, Long> {

    /** 取 namespace 下当前生效（ends_at > now）的 silence 规则。 */
    List<AlertSilencePo> findByNamespaceAndEndsAtAfter(String namespace, Instant now);
}
