package com.imsw.observe.config.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineDefinitionRepository extends JpaRepository<PipelineDefinitionPo, Long> {

    Optional<PipelineDefinitionPo> findByNamespaceAndName(String namespace, String name);

    List<PipelineDefinitionPo> findAllByNamespace(String namespace);

    boolean existsByNamespaceAndName(String namespace, String name);
}
