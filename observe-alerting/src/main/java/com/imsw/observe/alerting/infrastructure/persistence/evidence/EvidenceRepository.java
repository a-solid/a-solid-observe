package com.imsw.observe.alerting.infrastructure.persistence.evidence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceRepository extends JpaRepository<EvidencePo, Long> {

    /** 兼容旧调用：返回某 alert 的第一条证据（B7 后多为 1:N，优先用 {@link #findAllByAlertIdOrderByCapturedAtAsc}）。 */
    Optional<EvidencePo> findFirstByAlertIdOrderByCapturedAtAsc(Long alertId);

    /** ADR-0005 §2：1:N 读取，按捕获时间升序。 */
    List<EvidencePo> findAllByAlertIdOrderByCapturedAtAsc(Long alertId);

    /** 用于计算 emit_seq（当前 alert 已有证据条数）。 */
    long countByAlertId(Long alertId);
}
