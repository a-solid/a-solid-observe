package com.imsw.observe.controlplane.interfaces.dto;

import java.util.List;
import java.util.Map;

import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.pipeline.application.DryRunService;

public record DryRunResultDto(
        String outcome, List<Map<String, Object>> alerts, Map<String, Map<String, Object>> nodeOutputs) {

    public static DryRunResultDto from(final DryRunService.DryRunResult result) {
        if (result == null) {
            return null;
        }
        List<Map<String, Object>> alerts = result.alerts() == null
                ? List.of()
                : result.alerts().stream().map(DryRunResultDto::alertToMap).toList();
        return new DryRunResultDto(result.outcome(), alerts, result.nodeOutputs());
    }

    private static Map<String, Object> alertToMap(final AlertSignal signal) {
        return Map.of(
                "fingerprint", signal.fingerprint() == null ? "" : signal.fingerprint(),
                "severity", signal.severity() == null ? "" : signal.severity().name(),
                "labels", signal.labels() == null ? Map.of() : signal.labels(),
                "annotations", signal.annotations() == null ? Map.of() : signal.annotations(),
                "shortCircuit", signal.shortCircuit());
    }
}
