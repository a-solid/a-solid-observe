package com.imsw.observe.alerting.infrastructure.alert;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.alerting.infrastructure.fingerprint.AlertFingerprintCalculator;
import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.alert.model.AlertSpec;
import com.imsw.observe.kernel.alert.model.Severity;
import com.imsw.observe.kernel.alert.spi.AlertsApi;
import com.imsw.observe.kernel.event.model.ExecutionContext;

public final class DefaultAlertsApi implements AlertsApi {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultAlertsApi.class);

    private final ExecutionContext ctx;

    public DefaultAlertsApi(final ExecutionContext ctx) {
        this.ctx = ctx;
    }

    public DefaultAlertsApi(final ExecutionContext ctx, final String pipelineId) {
        this(ctx);
    }

    @Override
    public void emit(final AlertSpec spec) {
        AlertSignal signal = toSignal(spec);
        ctx.emitAlert(signal);
        LOG.info(
                "alert emitted pipeline={} severity={} fingerprint={} labels={}",
                ctx.meta().pipelineId(),
                signal.severity(),
                signal.fingerprint(),
                signal.labels());
    }

    @Override
    public void emit(final Severity severity, final Map<String, String> labels, final Map<String, String> annotations) {
        Map<String, Object> annotationsObject = new HashMap<>();
        if (annotations != null) {
            annotations.forEach(annotationsObject::put);
        }
        emit(new AlertSpec(null, severity, labels, annotationsObject, false, null));
    }

    private AlertSignal toSignal(final AlertSpec spec) {
        Map<String, String> labels = spec.labels() == null ? Map.of() : spec.labels();
        Map<String, Object> annotations = spec.annotations() == null ? Map.of() : spec.annotations();
        String fingerprint = spec.fingerprint() == null
                ? AlertFingerprintCalculator.compute(ctx.meta().pipelineId(), labels)
                : spec.fingerprint();
        return new AlertSignal(fingerprint, spec.severity(), labels, annotations, spec.shortCircuit(), spec.ttl());
    }
}
