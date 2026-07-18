package com.imsw.observe.controlplane.interfaces;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.imsw.observe.config.application.NamespaceCrudService;
import com.imsw.observe.controlplane.interfaces.dto.NamespaceDto;

@RestController
@RequestMapping("/api/v1/namespaces")
public class NamespaceController {

    private final NamespaceCrudService service;

    public NamespaceController(final NamespaceCrudService service) {
        this.service = service;
    }

    @PostMapping
    public NamespaceDto create(@RequestBody final CreateNamespaceRequest req) {
        return NamespaceDto.from(service.create(req.name(), req.displayName()));
    }

    @GetMapping
    public List<NamespaceDto> list() {
        return service.findAll().stream().map(NamespaceDto::from).toList();
    }

    @GetMapping("/{name}")
    public ResponseEntity<NamespaceDto> get(@PathVariable final String name) {
        NamespaceDto dto = NamespaceDto.from(service.findByName(name));
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    @PutMapping("/{name}")
    public NamespaceDto update(@PathVariable final String name, @RequestBody final UpdateNamespaceRequest req) {
        return NamespaceDto.from(service.updateDisplayName(name, req.displayName()));
    }

    @DeleteMapping("/{name}")
    public void delete(@PathVariable final String name) {
        service.delete(name);
    }

    public record CreateNamespaceRequest(String name, String displayName) {}

    public record UpdateNamespaceRequest(String displayName) {}
}
