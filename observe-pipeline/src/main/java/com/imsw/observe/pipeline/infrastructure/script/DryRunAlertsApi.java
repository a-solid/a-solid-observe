package com.imsw.observe.pipeline.infrastructure.script;

import java.util.HashMap;
import java.util.Map;

import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.alert.model.AlertSpec;
import com.imsw.observe.kernel.alert.model.Severity;
import com.imsw.observe.kernel.alert.spi.AlertsApi;
import com.imsw.observe.kernel.event.model.ExecutionContext;

public final class DryRunAlertsApi implements AlertsApi {

    private final ExecutionContext ctx;

    public DryRunAlertsApi(final ExecutionContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void emit(final AlertSpec spec) {
        Map<String, String> labels = spec.labels() == null ? Map.of() : spec.labels();
        Map<String, Object> annotations = spec.annotations() == null ? Map.of() : spec.annotations();
        AlertSignal signal = new AlertSignal(
                spec.fingerprint() == null ? "dry-run" : spec.fingerprint(),
                spec.severity() == null ? Severity.INFO : spec.severity(),
                labels,
                new HashMap<>(annotations),
                spec.shortCircuit(),
                spec.ttl());
        ctx.emitAlert(signal);
    }

    @Override
    public void emit(final Severity severity, final Map<String, String> labels, final Map<String, String> annotations) {
        Map<String, Object> annotationsObject = new HashMap<>();
        if (annotations != null) {
            annotations.forEach(annotationsObject::put);
        }
        emit(new AlertSpec(null, severity, labels, annotationsObject, false, null));
    }
}
