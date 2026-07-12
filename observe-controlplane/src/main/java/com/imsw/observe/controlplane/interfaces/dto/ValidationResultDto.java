package com.imsw.observe.controlplane.interfaces.dto;

import java.util.List;

import com.imsw.observe.config.application.PipelineValidator;

public record ValidationResultDto(boolean ok, List<String> errors) {

    public static ValidationResultDto from(final PipelineValidator.ValidationResult result) {
        if (result == null) {
            return null;
        }
        return new ValidationResultDto(result.ok(), result.errors());
    }
}
