package com.imsw.observe.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.imsw.observe.config.domain.Namespace;
import com.imsw.observe.config.infrastructure.persistence.NamespaceRepository;
import com.imsw.observe.config.infrastructure.persistence.TestJpaFactory;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

/**
 * Namespace CRUD 集成测试：打真实 H2 + JPA（{@link TestJpaFactory}），覆盖 create/find/duplicate/blank/update/delete。
 *
 * <p>测试基建选择说明：brief 给的 {@code TestJpaFactory.namespaceRepository()} 静态工厂在本模块不存在，
 * 也不符合 observe-pipeline 同名类（{@code @Configuration}，被 {@code @SpringBootTest(classes=...)} 装载）的形态。
 * 本模块此前唯一的 repository 相关测试（{@code PipelineCrudServiceTest}）是 Mockito 纯单测，
 * 但 Namespace CRUD 的核心语义（create 后 {@code findByName} 命中、delete 后查不到）必须落真实库才有意义，
 * 故采用与 pipeline 模块一致的 {@code @SpringBootTest(classes = TestJpaFactory.class)} 风格。
 */
@SpringBootTest(classes = TestJpaFactory.class)
class NamespaceCrudServiceTest {

    @Autowired
    private NamespaceRepository repository;

    private NamespaceCrudService service;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        service = new NamespaceCrudService(repository, new SnowflakeIdGenerator(1L, 0L));
    }

    @Test
    void createAssignsSnowflakeIdAndPersists() {
        Namespace ns = service.create("payments", "Payments Team");
        assertThat(ns.id()).isPositive();
        assertThat(ns.name()).isEqualTo("payments");
        assertThat(service.findByName("payments")).isNotNull();
    }

    @Test
    void createRejectsDuplicateName() {
        service.create("payments", "Payments");
        assertThatThrownBy(() -> service.create("payments", "Other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace already exists");
    }

    @Test
    void createRejectsBlankName() {
        assertThatThrownBy(() -> service.create("  ", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateDisplayNameKeepsNameAndId() {
        Namespace ns = service.create("payments", "Old");
        Namespace updated = service.updateDisplayName("payments", "New");
        assertThat(updated.displayName()).isEqualTo("New");
        assertThat(updated.name()).isEqualTo("payments");
        assertThat(updated.id()).isEqualTo(ns.id());
    }

    @Test
    void deleteRemovesNamespace() {
        service.create("payments", "P");
        service.delete("payments");
        assertThat(service.findByName("payments")).isNull();
    }
}
