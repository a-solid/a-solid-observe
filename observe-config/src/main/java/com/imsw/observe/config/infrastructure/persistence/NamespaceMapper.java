package com.imsw.observe.config.infrastructure.persistence;

import com.imsw.observe.config.domain.Namespace;

public final class NamespaceMapper {

    private NamespaceMapper() {}

    public static Namespace toEntity(final NamespacePo po) {
        if (po == null) {
            return null;
        }
        return new Namespace(po.id, po.name, po.displayName, po.createdAt, po.updatedAt);
    }

    public static NamespacePo toPo(final Namespace entity) {
        if (entity == null) {
            return null;
        }
        NamespacePo po = new NamespacePo();
        po.id = entity.id();
        po.name = entity.name();
        po.displayName = entity.displayName();
        po.createdAt = entity.createdAt();
        po.updatedAt = entity.updatedAt();
        return po;
    }
}
