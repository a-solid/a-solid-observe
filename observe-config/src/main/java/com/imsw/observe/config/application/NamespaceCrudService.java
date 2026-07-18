package com.imsw.observe.config.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.imsw.observe.config.domain.Namespace;
import com.imsw.observe.config.infrastructure.persistence.NamespaceMapper;
import com.imsw.observe.config.infrastructure.persistence.NamespacePo;
import com.imsw.observe.config.infrastructure.persistence.NamespaceRepository;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

@Service
public class NamespaceCrudService {

    private final NamespaceRepository repository;

    private final SnowflakeIdGenerator snowflakeIdGenerator;

    public NamespaceCrudService(final NamespaceRepository repository, final SnowflakeIdGenerator snowflakeIdGenerator) {
        this.repository = repository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
    }

    @Transactional
    public Namespace create(final String name, final String displayName) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("namespace name must not be blank");
        }
        if (repository.findByName(name).isPresent()) {
            throw new IllegalArgumentException("namespace already exists: " + name);
        }
        NamespacePo po = new NamespacePo();
        po.id = snowflakeIdGenerator.next();
        po.name = name;
        po.displayName = displayName;
        Instant now = Instant.now();
        po.createdAt = now;
        po.updatedAt = now;
        return NamespaceMapper.toEntity(repository.save(po));
    }

    @Transactional(readOnly = true)
    public Namespace findByName(final String name) {
        return repository.findByName(name).map(NamespaceMapper::toEntity).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<Namespace> findAll() {
        return repository.findAll().stream().map(NamespaceMapper::toEntity).toList();
    }

    @Transactional
    public Namespace updateDisplayName(final String name, final String displayName) {
        NamespacePo po = repository
                .findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("namespace not found: " + name));
        po.displayName = displayName;
        po.updatedAt = Instant.now();
        return NamespaceMapper.toEntity(repository.save(po));
    }

    @Transactional
    public void delete(final String name) {
        NamespacePo po = repository
                .findByName(name)
                .orElseThrow(() -> new IllegalArgumentException("namespace not found: " + name));
        repository.delete(po);
    }
}
