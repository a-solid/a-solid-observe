package com.imsw.observe.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.imsw.observe.config.domain.Namespace;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionRepository;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

class PipelineCrudServiceTest {

    @Test
    void createAllocatesIdFromSnowflake() {
        PipelineDefinitionRepository repository = Mockito.mock(PipelineDefinitionRepository.class);
        NamespaceCrudService namespaceCrudService = Mockito.mock(NamespaceCrudService.class);
        // namespace must exist (软隔离铁律 ADR-0002)，否则 create 在写库前就被拒。
        when(namespaceCrudService.findByName(eq("payments")))
                .thenReturn(new Namespace(1L, "payments", "Payments", null, null));
        when(repository.existsByNamespaceAndName(eq("payments"), eq("checkout")))
                .thenReturn(false);
        when(repository.save(any(PipelineDefinitionPo.class))).thenAnswer(inv -> inv.getArgument(0));

        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);
        PipelineCrudService service = new PipelineCrudService(repository, generator, namespaceCrudService);

        var def = service.create("payments", "checkout", "team-a", "Order Pipeline", Map.of(), "desc", "alice");

        // id is allocated by the generator (positive snowflake), not caller-supplied.
        assertThat(def.id()).isNotNull();
        assertThat(def.id()).isPositive();
        assertThat(def.namespace()).isEqualTo("payments");

        // existence was checked against the (namespace, name) business key.
        verify(repository, times(1)).existsByNamespaceAndName("payments", "checkout");

        // the persisted po carries the allocated id (no caller id ever entered the service) and namespace.
        ArgumentCaptor<PipelineDefinitionPo> captor = ArgumentCaptor.forClass(PipelineDefinitionPo.class);
        verify(repository, times(1)).save(captor.capture());
        assertThat(captor.getValue().id).isEqualTo(def.id());
        assertThat(captor.getValue().namespace).isEqualTo("payments");
    }

    @Test
    void createDoesNotAcceptCallerSuppliedId() throws NoSuchMethodException {
        // Behavioral contract: create has no id parameter; id is internally allocated.
        // Signature: (namespace, name, team, application, labels, description, createdBy) — 7 params.
        var create = PipelineCrudService.class.getMethod(
                "create",
                String.class,
                String.class,
                String.class,
                String.class,
                Map.class,
                String.class,
                String.class);
        assertThat(create.getParameters()).hasSize(7);
    }

    @Test
    void createRejectsUnknownNamespace() {
        // 软隔离铁律（ADR-0002）：namespace 必须存在；NamespaceCrudService.findByName 返回 null 时，
        // create 必须在写库前被拒（a-solid-observe 不使用 FK 约束，靠应用层校验）。
        PipelineDefinitionRepository repository = Mockito.mock(PipelineDefinitionRepository.class);
        NamespaceCrudService namespaceCrudService = Mockito.mock(NamespaceCrudService.class);
        when(namespaceCrudService.findByName(eq("ghost"))).thenReturn(null);

        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);
        PipelineCrudService service = new PipelineCrudService(repository, generator, namespaceCrudService);

        assertThatThrownBy(() ->
                        service.create("ghost", "checkout", "team-a", "Order Pipeline", Map.of(), "desc", "alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace not found")
                .hasMessageContaining("ghost");

        // 写库绝不能被触达。
        verify(repository, times(0)).save(any(PipelineDefinitionPo.class));
        verify(repository, times(0)).existsByNamespaceAndName(any(), any());
    }
}
