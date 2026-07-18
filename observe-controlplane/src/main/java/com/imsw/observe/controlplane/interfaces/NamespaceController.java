package com.imsw.observe.controlplane.interfaces;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

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
import com.imsw.observe.controlplane.interfaces.web.ApiResponse;
import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;

@RestController
@RequestMapping("/api/v1/namespaces")
public class NamespaceController {

    private final NamespaceCrudService service;

    public NamespaceController(final NamespaceCrudService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<NamespaceDto> create(@Valid @RequestBody final CreateNamespaceRequest req) {
        return ApiResponse.ok(NamespaceDto.from(service.create(req.name(), req.displayName())));
    }

    @GetMapping
    public ApiResponse<List<NamespaceDto>> list() {
        return ApiResponse.ok(service.findAll().stream().map(NamespaceDto::from).toList());
    }

    @GetMapping("/{name}")
    public ApiResponse<NamespaceDto> get(@PathVariable final String name) {
        NamespaceDto dto = NamespaceDto.from(service.findByName(name));
        if (dto == null) {
            throw new ResourceNotFoundException("namespace " + name + " not found");
        }
        return ApiResponse.ok(dto);
    }

    @PutMapping("/{name}")
    public ApiResponse<NamespaceDto> update(
            @PathVariable final String name, @Valid @RequestBody final UpdateNamespaceRequest req) {
        return ApiResponse.ok(NamespaceDto.from(service.updateDisplayName(name, req.displayName())));
    }

    @DeleteMapping("/{name}")
    public ApiResponse<Void> delete(@PathVariable final String name) {
        service.delete(name);
        return ApiResponse.ok(null);
    }

    public record CreateNamespaceRequest(@NotBlank String name, String displayName) {}

    public record UpdateNamespaceRequest(String displayName) {}
}
