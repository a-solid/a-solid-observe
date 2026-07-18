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

    public PipelineCrudService(
            final PipelineDefinitionRepository repository, final SnowflakeIdGenerator snowflakeIdGenerator) {
        this.repository = repository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Transactional
    public PipelineDefinition create(
            final String team,
            final String application,
            final Map<String, String> labels,
            final String name,
            final String description,
            final String createdBy) {
        long id = snowflakeIdGenerator.next();
        if (repository.existsById(id)) {
            throw new IllegalArgumentException("pipeline already exists: " + id);
        }
        PipelineDefinitionPo po = new PipelineDefinitionPo();
        po.id = id;
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
            final Long id,
            final String team,
            final String application,
            final Map<String, String> labels,
            final String name,
            final String description) {
        PipelineDefinitionPo po =
                repository.findById(id).orElseThrow(() -> new IllegalArgumentException("pipeline not found: " + id));
        po.team = team;
        po.application = application;
        po.labels = labels;
        po.name = name;
        po.description = description;
        po.updatedAt = Instant.now();
        return PipelineDefinitionMapper.toEntity(repository.save(po));
    }

    @Transactional
    public void archive(final Long id) {
        PipelineDefinitionPo po =
                repository.findById(id).orElseThrow(() -> new IllegalArgumentException("pipeline not found: " + id));
        po.status = "ARCHIVED";
        po.updatedAt = Instant.now();
        repository.save(po);
    }

    @Transactional(readOnly = true)
    public PipelineDefinition find(final Long id) {
        return repository.findById(id).map(PipelineDefinitionMapper::toEntity).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PipelineDefinition> findAll() {
        return repository.findAll().stream()
                .map(PipelineDefinitionMapper::toEntity)
                .toList();
    }
}
