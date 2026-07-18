package com.imsw.observe.config.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<SubscriptionPo, Long> {

    Optional<SubscriptionPo> findByNamespaceAndName(String namespace, String name);

    List<SubscriptionPo> findAllByNamespace(String namespace);

    boolean existsByNamespaceAndName(String namespace, String name);
}
