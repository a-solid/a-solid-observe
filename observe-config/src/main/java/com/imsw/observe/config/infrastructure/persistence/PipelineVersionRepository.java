package com.imsw.observe.config.infrastructure.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineVersionRepository extends JpaRepository<PipelineVersionPo, PipelineVersionPk> {

    List<PipelineVersionPo> findAllByNamespace(String namespace);
}
