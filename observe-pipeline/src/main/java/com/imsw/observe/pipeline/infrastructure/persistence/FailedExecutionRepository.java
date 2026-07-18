package com.imsw.observe.pipeline.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FailedExecutionRepository extends JpaRepository<FailedExecutionPo, Long> {}
