package com.imsw.observe.controlplane.interfaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.imsw.observe.config.application.NamespaceCrudService;
import com.imsw.observe.config.domain.Namespace;
import com.imsw.observe.controlplane.interfaces.dto.NamespaceDto;
import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;

/**
 * Plain Mockito unit test mirroring {@code EventControllerTest} — no Spring context.
 * The controller is a thin adapter; service-level behavior is covered by
 * {@code NamespaceCrudServiceTest}.
 *
 * <p>B5：响应包入 {@code ApiResponse}；404 改为抛 {@link ResourceNotFoundException}（含错误体），由
 * {@code GlobalExceptionHandlerWebTest} 覆盖 HTTP 语义。
 */
class NamespaceControllerTest {

    private NamespaceCrudService service;

    private NamespaceController controller;

    @BeforeEach
    void setUp() {
        service = mock(NamespaceCrudService.class);
        controller = new NamespaceController(service);
    }

    @Test
    void createDelegatesAndMapsToDto() {
        Namespace created = new Namespace(101L, "team-a", "Team A", null, null);
        when(service.create("team-a", "Team A")).thenReturn(created);

        NamespaceDto dto = controller
                .create(new NamespaceController.CreateNamespaceRequest("team-a", "Team A"))
                .data();

        assertEquals(101L, dto.id());
        assertEquals("team-a", dto.name());
        assertEquals("Team A", dto.displayName());
        verify(service).create(eq("team-a"), eq("Team A"));
    }

    @Test
    void listMapsAllNamespaces() {
        when(service.findAll())
                .thenReturn(List.of(new Namespace(1L, "a", "A", null, null), new Namespace(2L, "b", "B", null, null)));

        List<NamespaceDto> dtos = controller.list().data();

        assertEquals(2, dtos.size());
        assertEquals("a", dtos.get(0).name());
        assertEquals("b", dtos.get(1).name());
    }

    @Test
    void getReturnsOkWhenFound() {
        when(service.findByName("team-a")).thenReturn(new Namespace(7L, "team-a", "Team A", null, null));

        NamespaceDto dto = controller.get("team-a").data();

        assertNotNull(dto);
        assertEquals(7L, dto.id());
    }

    @Test
    void getThrowsNotFoundWhenMissing() {
        when(service.findByName("ghost")).thenReturn(null);

        assertThrows(ResourceNotFoundException.class, () -> controller.get("ghost"));
    }

    @Test
    void updateDelegatesToUpdateDisplayName() {
        when(service.updateDisplayName("team-a", "Renamed"))
                .thenReturn(new Namespace(7L, "team-a", "Renamed", null, null));

        NamespaceDto dto = controller
                .update("team-a", new NamespaceController.UpdateNamespaceRequest("Renamed"))
                .data();

        assertEquals("Renamed", dto.displayName());
        verify(service).updateDisplayName(eq("team-a"), eq("Renamed"));
    }

    @Test
    void fromReturnsNullForNullNamespace() {
        assertNull(NamespaceDto.from(null));
    }
}
