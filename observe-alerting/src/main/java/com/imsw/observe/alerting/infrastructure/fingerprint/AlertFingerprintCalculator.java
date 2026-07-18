package com.imsw.observe.alerting.infrastructure.fingerprint;

import java.util.Map;
import java.util.TreeMap;

import com.imsw.observe.kernel.util.HashUtil;

public final class AlertFingerprintCalculator {

    private AlertFingerprintCalculator() {}

    public static String compute(final Long pipelineId, final Map<String, String> labels) {
        TreeMap<String, String> sorted = labels == null ? new TreeMap<>() : new TreeMap<>(labels);
        StringBuilder sb = new StringBuilder();
        sb.append("pipeline=").append(pipelineId == null ? "" : pipelineId);
        sorted.forEach((k, v) -> sb.append(';').append(k).append('=').append(v));
        return HashUtil.sha256(sb.toString());
    }
}
