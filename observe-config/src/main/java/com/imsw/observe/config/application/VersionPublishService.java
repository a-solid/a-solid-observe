package com.imsw.observe.config.application;

import java.time.Instant;
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
        // 软隔离铁律（ADR-0002）：body namespace 必须与路径一致或留空（注入）。
        // definitionJson 独立自包含全量数据（包括服务端字段），loader 直接反序列化即用。
        if (pipeline != null
                && pipeline.namespace() != null
                && !pipeline.namespace().isBlank()
                && !pipeline.namespace().equals(def.namespace)) {
            throw new IllegalArgumentException("pipeline body namespace '" + pipeline.namespace()
                    + "' must match path namespace '" + def.namespace + "'");
        }
        int ver = nextVersion(def.id);
        Instant now = Instant.now();
        Pipeline normalized =
                normalize(pipeline, def.id, def.namespace, def.name, ver, Pipeline.Status.DRAFT, now, null);
        String json = JsonUtil.toJson(normalized);
        PipelineVersionPo po = new PipelineVersionPo();
        po.namespace = def.namespace;
        po.pipelineId = def.id;
        po.version = ver;
        po.definitionJson = json;
        po.definitionHash = HashUtil.sha256(json);
        po.status = "DRAFT";
        po.publishedBy = publishedBy;
        po.createdAt = now;
        versionRepository.save(po);
        def.updatedAt = now;
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
        Instant now = Instant.now();
        // 重序列化 definitionJson：status 和 publishedAt 与版本 PO 一致，loader 反序列化后语义正确。
        Pipeline current = JsonUtil.fromJson(po.definitionJson, Pipeline.class);
        if (current != null) {
            Pipeline published = normalize(
                    current,
                    def.id,
                    def.namespace,
                    def.name,
                    version,
                    Pipeline.Status.PUBLISHED,
                    current.createdAt(),
                    now);
            po.definitionJson = JsonUtil.toJson(published);
            po.definitionHash = HashUtil.sha256(po.definitionJson);
        }
        po.status = "PUBLISHED";
        po.publishedBy = publishedBy;
        po.publishedAt = now;
        versionRepository.save(po);
        def.status = "PUBLISHED";
        def.currentVersion = version;
        def.updatedAt = now;
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
        // namespace 已通过 requireDefinition 软校验（def 即该 namespace 下的 pipeline），
        // 直接按 pipelineId 查版本，避免 findAll + 内存过滤扫描全表。
        return versionRepository.findAllByPipelineIdOrderByVersionAsc(def.id).stream()
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
        return versionRepository.findAllByPipelineIdOrderByVersionAsc(pipelineId).stream()
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

    /** 注入全部服务端字段到 pipeline body，确保 definitionJson 独立自包含。 */
    static Pipeline normalize(
            final Pipeline body,
            final Long id,
            final String namespace,
            final String name,
            final int version,
            final Pipeline.Status status,
            final Instant createdAt,
            final Instant publishedAt) {
        if (body == null) {
            return null;
        }
        return new Pipeline(
                id,
                namespace,
                version,
                body.labels(),
                name,
                status,
                body.nodes(),
                createdAt,
                publishedAt,
                body.executionLogSampleRatio());
    }
}
