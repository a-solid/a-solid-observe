package com.imsw.observe.config.infrastructure.persistence;

import com.imsw.observe.config.domain.PipelineDefinition;

public final class PipelineDefinitionMapper {

    private PipelineDefinitionMapper() {}

    public static PipelineDefinition toEntity(final PipelineDefinitionPo po) {
        if (po == null) {
            return null;
        }
        return new PipelineDefinition(
                po.id,
                po.namespace,
                po.team,
                po.application,
                po.labels,
                po.name,
                po.description,
                po.status == null ? null : PipelineDefinition.Status.valueOf(po.status),
                po.currentVersion,
                po.createdBy,
                po.createdAt,
                po.updatedAt);
    }

    public static PipelineDefinitionPo toPo(final PipelineDefinition entity) {
        if (entity == null) {
            return null;
        }
        PipelineDefinitionPo po = new PipelineDefinitionPo();
        po.id = entity.id();
        po.namespace = entity.namespace();
        po.team = entity.team();
        po.application = entity.application();
        po.labels = entity.labels();
        po.name = entity.name();
        po.description = entity.description();
        po.status = entity.status() == null ? null : entity.status().name();
        po.currentVersion = entity.currentVersion();
        po.createdBy = entity.createdBy();
        po.createdAt = entity.createdAt();
        po.updatedAt = entity.updatedAt();
        return po;
    }
}
