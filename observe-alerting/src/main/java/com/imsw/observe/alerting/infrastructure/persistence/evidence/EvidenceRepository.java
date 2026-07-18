package com.imsw.observe.alerting.infrastructure.persistence.evidence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceRepository extends JpaRepository<EvidencePo, Long> {

    Optional<EvidencePo> findByAlertId(Long alertId);
}
