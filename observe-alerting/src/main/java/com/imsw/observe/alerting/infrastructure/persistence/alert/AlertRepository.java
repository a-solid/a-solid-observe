package com.imsw.observe.alerting.infrastructure.persistence.alert;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AlertRepository extends JpaRepository<AlertPo, Long> {

    Optional<AlertPo> findFirstByFingerprintAndStatusOrderByIdAsc(String fingerprint, String status);

    List<AlertPo> findByStatusOrderByIdDesc(String status, Pageable pageable);

    @Query("select a.id from AlertPo a where a.status = 'FIRING' and a.endsAt < :now order by a.id")
    List<Long> findExpiredFiringIds(@Param("now") Instant now, Pageable pageable);

    @Modifying
    @Query("update AlertPo a set a.lastSeenAt = :now, a.endsAt = :endsAt, "
            + "a.dedupCount = a.dedupCount + 1, a.updatedAt = :now where a.id = :id")
    int updateEmit(@Param("id") Long id, @Param("now") Instant now, @Param("endsAt") Instant endsAt);

    @Modifying
    @Query("update AlertPo a set a.status = 'RESOLVED', a.resolvedAt = a.endsAt, a.updatedAt = :now "
            + "where a.id in :ids and a.status = 'FIRING'")
    int resolveBatch(@Param("ids") List<Long> ids, @Param("now") Instant now);
}
