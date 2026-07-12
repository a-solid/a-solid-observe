package com.imsw.observe.config.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineVersionRepository extends JpaRepository<PipelineVersionPo, PipelineVersionPk> {}
