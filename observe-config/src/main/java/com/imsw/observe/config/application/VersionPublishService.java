package com.imsw.observe.config.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imsw.observe.config.domain.PipelineVersion;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionRepository;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineVersionRepository;
import com.imsw.observe.kernel.util.HashUtil;
import com.imsw.observe.kernel.util.JsonUtil;
import com.imsw.observe.pipeline.domain.Pipeline;

@Service
public class VersionPublishService {

    private final PipelineVersionRepository versionRepository;

    private final PipelineDefinitionRepository definitionRepository;

    public VersionPublishService(
            final PipelineVersionRepository versionRepository,
            final PipelineDefinitionRepository definitionRepository) {
        this.versionRepository = versionRepository;
        this.definitionRepository = definitionRepository;
    }

    @Transactional
    public PipelineVersion saveDraft(
            final String namespace, final String pipelineName, final Pipeline pipeline, final String publishedBy) {
        PipelineDefinitionPo def = requireDefinition(namespace, pipelineName);
        String json = JsonUtil.toJson(pipeline);
        int nextVersion = nextVersion(def.id);
        PipelineVersionPo po = new PipelineVersionPo();
        po.namespace = def.namespace;
        po.pipelineId = def.id;
        po.version = nextVersion;
        po.definitionJson = json;
        po.definitionHash = HashUtil.sha256(json);
        po.status = "DRAFT";
        po.publishedBy = publishedBy;
        po.createdAt = Instant.now();
        versionRepository.save(po);
        def.updatedAt = Instant.now();
        definitionRepository.save(def);
        return toEntity(po);
    }

    @Transactional
    public PipelineVersion publish(
            final String namespace, final String pipelineName, final int version, final String publishedBy) {
        PipelineDefinitionPo def = requireDefinition(namespace, pipelineName);
        PipelineVersionPo po = versionRepository
                .findById(versionPk(def.id, version))
                .orElseThrow(
                        () -> new IllegalArgumentException("pipeline version not found: " + def.id + " v" + version));
        po.status = "PUBLISHED";
        po.publishedBy = publishedBy;
        po.publishedAt = Instant.now();
        versionRepository.save(po);
        def.status = "PUBLISHED";
        def.currentVersion = version;
        def.updatedAt = Instant.now();
        definitionRepository.save(def);
        return toEntity(po);
    }

    @Transactional
    public PipelineVersion archive(final String namespace, final String pipelineName, final int version) {
        PipelineDefinitionPo def = requireDefinition(namespace, pipelineName);
        PipelineVersionPo po = versionRepository
                .findById(versionPk(def.id, version))
                .orElseThrow(
                        () -> new IllegalArgumentException("pipeline version not found: " + def.id + " v" + version));
        po.status = "ARCHIVED";
        versionRepository.save(po);
        return toEntity(po);
    }

    @Transactional(readOnly = true)
    public List<PipelineVersion> versions(final String namespace, final String pipelineName) {
        PipelineDefinitionPo def = requireDefinition(namespace, pipelineName);
        return versionRepository.findAll().stream()
                .filter(v -> def.id.equals(v.pipelineId))
                .sorted(Comparator.comparingInt((PipelineVersionPo v) -> v.version))
                .map(VersionPublishService::toEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public PipelineVersion find(final String namespace, final String pipelineName, final int version) {
        PipelineDefinitionPo def = requireDefinition(namespace, pipelineName);
        return versionRepository
                .findById(versionPk(def.id, version))
                .map(VersionPublishService::toEntity)
                .orElse(null);
    }

    @Transactional
    public int nextVersion(final Long pipelineId) {
        return versionRepository.findAll().stream()
                        .filter(v -> pipelineId.equals(v.pipelineId))
                        .mapToInt(v -> v.version)
                        .max()
                        .orElse(0)
                + 1;
    }

    private PipelineDefinitionPo requireDefinition(final String namespace, final String pipelineName) {
        return definitionRepository
                .findByNamespaceAndName(namespace, pipelineName)
                .orElseThrow(
                        () -> new IllegalArgumentException("pipeline not found: " + namespace + "/" + pipelineName));
    }

    private static com.imsw.observe.config.infrastructure.persistence.PipelineVersionPk versionPk(
            final Long pipelineId, final int version) {
        return new com.imsw.observe.config.infrastructure.persistence.PipelineVersionPk(pipelineId, version);
    }

    private static PipelineVersion toEntity(final PipelineVersionPo po) {
        return new PipelineVersion(
                po.namespace,
                po.pipelineId,
                po.version,
                po.definitionJson,
                po.definitionHash,
                po.status == null ? null : PipelineVersion.Status.valueOf(po.status),
                po.publishedBy,
                po.createdAt,
                po.publishedAt);
    }
}
