package com.imsw.observe.controlplane.interfaces.dto;

import com.imsw.observe.config.domain.Namespace;

public record NamespaceDto(Long id, String name, String displayName) {

    public static NamespaceDto from(final Namespace ns) {
        return ns == null ? null : new NamespaceDto(ns.id(), ns.name(), ns.displayName());
    }
}
