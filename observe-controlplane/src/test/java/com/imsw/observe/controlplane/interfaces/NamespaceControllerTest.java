package com.imsw.observe.controlplane.interfaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.imsw.observe.config.application.NamespaceCrudService;
import com.imsw.observe.config.domain.Namespace;
import com.imsw.observe.controlplane.interfaces.dto.NamespaceDto;

/**
 * Plain Mockito unit test mirroring {@code EventControllerTest} — no Spring context.
 * The controller is a thin adapter; service-level behavior is covered by
 * {@code NamespaceCrudServiceTest}.
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

        NamespaceDto dto = controller.create(new NamespaceController.CreateNamespaceRequest("team-a", "Team A"));

        assertEquals(101L, dto.id());
        assertEquals("team-a", dto.name());
        assertEquals("Team A", dto.displayName());
        verify(service).create(eq("team-a"), eq("Team A"));
    }

    @Test
    void listMapsAllNamespaces() {
        when(service.findAll())
                .thenReturn(List.of(new Namespace(1L, "a", "A", null, null), new Namespace(2L, "b", "B", null, null)));

        List<NamespaceDto> dtos = controller.list();

        assertEquals(2, dtos.size());
        assertEquals("a", dtos.get(0).name());
        assertEquals("b", dtos.get(1).name());
    }

    @Test
    void getReturnsOkWhenFound() {
        when(service.findByName("team-a")).thenReturn(new Namespace(7L, "team-a", "Team A", null, null));

        NamespaceDto dto = controller.get("team-a").getBody();

        assertNotNull(dto);
        assertEquals(7L, dto.id());
    }

    @Test
    void getReturnsNotFoundWhenMissing() {
        when(service.findByName("ghost")).thenReturn(null);

        HttpStatus status = (HttpStatus) controller.get("ghost").getStatusCode();

        assertEquals(HttpStatus.NOT_FOUND, status);
    }

    @Test
    void updateDelegatesToUpdateDisplayName() {
        when(service.updateDisplayName("team-a", "Renamed"))
                .thenReturn(new Namespace(7L, "team-a", "Renamed", null, null));

        NamespaceDto dto = controller.update("team-a", new NamespaceController.UpdateNamespaceRequest("Renamed"));

        assertEquals("Renamed", dto.displayName());
        verify(service).updateDisplayName(eq("team-a"), eq("Renamed"));
    }

    @Test
    void fromReturnsNullForNullNamespace() {
        assertNull(NamespaceDto.from(null));
    }
}
