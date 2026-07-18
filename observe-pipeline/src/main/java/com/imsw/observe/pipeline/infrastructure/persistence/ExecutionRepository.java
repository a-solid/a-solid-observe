package com.imsw.observe.pipeline.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionRepository extends JpaRepository<ExecutionPo, Long> {}
