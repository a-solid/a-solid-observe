package com.imsw.observe.kernel.alert.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record AlertSignal(
        String fingerprint,
        Severity severity,
        Map<String, String> labels,
        Map<String, Object> annotations,
        EvidenceSpec evidence,
        boolean shortCircuit,
        Duration ttl) {

    public record EvidenceSpec(List<String> capture, boolean attachOutputs, boolean attachTriggerEvent) {}
}
