package com.imsw.observe.config.infrastructure.persistence;

import com.imsw.observe.config.domain.PipelineVersion;

public final class PipelineVersionMapper {

    private PipelineVersionMapper() {}

    public static PipelineVersion toEntity(final PipelineVersionPo po) {
        if (po == null) {
            return null;
        }
        return new PipelineVersion(
                po.pipelineId,
                po.version == null ? 0 : po.version,
                po.definitionJson,
                po.definitionHash,
                po.status == null ? null : PipelineVersion.Status.valueOf(po.status),
                po.publishedBy,
                po.createdAt,
                po.publishedAt);
    }

    public static PipelineVersionPo toPo(final PipelineVersion entity) {
        if (entity == null) {
            return null;
        }
        PipelineVersionPo po = new PipelineVersionPo();
        po.pipelineId = entity.pipelineId();
        po.version = entity.version();
        po.definitionJson = entity.definitionJson();
        po.definitionHash = entity.definitionHash();
        po.status = entity.status() == null ? null : entity.status().name();
        po.publishedBy = entity.publishedBy();
        po.createdAt = entity.createdAt();
        po.publishedAt = entity.publishedAt();
        return po;
    }
}
