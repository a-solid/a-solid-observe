package com.imsw.observe.config.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<SubscriptionPo, String> {}
