package com.imsw.observe.config.infrastructure.persistence;

import java.io.Serializable;
import java.util.Objects;

public final class PipelineVersionPk implements Serializable {

    public Long pipelineId;
    public Integer version;

    public PipelineVersionPk() {}

    public PipelineVersionPk(final Long pipelineId, final Integer version) {
        this.pipelineId = pipelineId;
        this.version = version;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PipelineVersionPk other)) {
            return false;
        }
        return Objects.equals(pipelineId, other.pipelineId) && Objects.equals(version, other.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pipelineId, version);
    }
}
