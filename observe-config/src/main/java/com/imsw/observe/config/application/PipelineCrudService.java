package com.imsw.observe.config.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imsw.observe.config.domain.PipelineDefinition;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionMapper;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionRepository;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

@Service
public class PipelineCrudService {

    private final PipelineDefinitionRepository repository;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    private final NamespaceCrudService namespaceCrudService;

    public PipelineCrudService(
            final PipelineDefinitionRepository repository,
            final SnowflakeIdGenerator snowflakeIdGenerator,
            final NamespaceCrudService namespaceCrudService) {
        this.repository = repository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.namespaceCrudService = namespaceCrudService;
    }

    @Transactional
    public PipelineDefinition create(
            final String namespace,
            final String name,
            final String team,
            final String application,
            final Map<String, String> labels,
            final String description,
            final String createdBy) {
        // 软隔离铁律：namespace 必须存在（应用层校验，a-solid-observe 不使用 FK 约束）。
        if (namespaceCrudService.findByName(namespace) == null) {
            throw new IllegalArgumentException("namespace not found: " + namespace);
        }
        if (repository.existsByNamespaceAndName(namespace, name)) {
            throw new IllegalArgumentException("pipeline already exists: " + namespace + "/" + name);
        }
        long id = snowflakeIdGenerator.next();
        PipelineDefinitionPo po = new PipelineDefinitionPo();
        po.id = id;
        po.namespace = namespace;
        po.team = team;
        po.application = application;
        po.labels = labels;
        po.name = name;
        po.description = description;
        po.status = "DRAFT";
        po.createdBy = createdBy;
        Instant now = Instant.now();
        po.createdAt = now;
        po.updatedAt = now;
        return PipelineDefinitionMapper.toEntity(repository.save(po));
    }

    @Transactional
    public PipelineDefinition update(
            final String namespace,
            final String name,
            final String team,
            final String application,
            final Map<String, String> labels,
            final String description) {
        PipelineDefinitionPo po = repository
                .findByNamespaceAndName(namespace, name)
                .orElseThrow(() -> new IllegalArgumentException("pipeline not found: " + namespace + "/" + name));
        po.team = team;
        po.application = application;
        po.labels = labels;
        po.description = description;
        po.updatedAt = Instant.now();
        return PipelineDefinitionMapper.toEntity(repository.save(po));
    }

    @Transactional
    public void archive(final String namespace, final String name) {
        PipelineDefinitionPo po = repository
                .findByNamespaceAndName(namespace, name)
                .orElseThrow(() -> new IllegalArgumentException("pipeline not found: " + namespace + "/" + name));
        po.status = "ARCHIVED";
        po.updatedAt = Instant.now();
        repository.save(po);
    }

    @Transactional(readOnly = true)
    public PipelineDefinition find(final String namespace, final String name) {
        return repository
                .findByNamespaceAndName(namespace, name)
                .map(PipelineDefinitionMapper::toEntity)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PipelineDefinition> findAll(final String namespace) {
        return repository.findAllByNamespace(namespace).stream()
                .map(PipelineDefinitionMapper::toEntity)
                .toList();
    }
}
