package com.imsw.observe.kernel.alert.model;

import java.time.Duration;
import java.util.Map;

public record AlertSpec(
        String fingerprint,
        Severity severity,
        Map<String, String> labels,
        Map<String, Object> annotations,
        AlertSignal.EvidenceSpec evidence,
        boolean shortCircuit,
        Duration ttl) {}
