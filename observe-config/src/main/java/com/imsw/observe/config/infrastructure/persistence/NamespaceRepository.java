package com.imsw.observe.config.infrastructure.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NamespaceRepository extends JpaRepository<NamespacePo, Long> {

    Optional<NamespacePo> findByName(String name);
}
