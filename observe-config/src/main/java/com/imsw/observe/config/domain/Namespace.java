package com.imsw.observe.config.domain;

import java.time.Instant;

public record Namespace(Long id, String name, String displayName, Instant createdAt, Instant updatedAt) {}
