package com.imsw.observe.kernel.alert.spi;

import java.util.Map;

import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.alert.model.AlertSpec;
import com.imsw.observe.kernel.alert.model.Severity;

public interface AlertsApi {

    void emit(AlertSpec spec);

    void emit(
            Severity severity,
            Map<String, String> labels,
            Map<String, String> annotations,
            AlertSignal.EvidenceSpec evidence);
}
