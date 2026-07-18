package com.imsw.observe.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionPo;
import com.imsw.observe.config.infrastructure.persistence.PipelineDefinitionRepository;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;

class PipelineCrudServiceTest {

    @Test
    void createAllocatesIdFromSnowflake() {
        PipelineDefinitionRepository repository = Mockito.mock(PipelineDefinitionRepository.class);
        when(repository.existsById(any())).thenReturn(false);
        when(repository.save(any(PipelineDefinitionPo.class))).thenAnswer(inv -> inv.getArgument(0));

        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(1L, 0L);
        PipelineCrudService service = new PipelineCrudService(repository, generator);

        var def = service.create("team-a", "checkout", Map.of(), "Order Pipeline", "desc", "alice");

        // id is allocated by the generator (positive snowflake), not caller-supplied.
        assertThat(def.id()).isNotNull();
        assertThat(def.id()).isPositive();

        // existence was checked against the same allocated id.
        verify(repository, times(1)).existsById(def.id());

        // the persisted po carries the allocated id (no caller id ever entered the service).
        ArgumentCaptor<PipelineDefinitionPo> captor = ArgumentCaptor.forClass(PipelineDefinitionPo.class);
        verify(repository, times(1)).save(captor.capture());
        assertThat(captor.getValue().id).isEqualTo(def.id());
    }

    @Test
    void createDoesNotAcceptCallerSuppliedId() throws NoSuchMethodException {
        // Behavioral contract: create has no id parameter; id is internally allocated.
        var create = PipelineCrudService.class.getMethod(
                "create", String.class, String.class, Map.class, String.class, String.class, String.class);
        assertThat(create.getParameters()).hasSize(6);
    }
}
