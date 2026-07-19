package com.imsw.observe.kernel.alert.spi;

import java.util.Map;

import com.imsw.observe.kernel.alert.model.AlertSpec;
import com.imsw.observe.kernel.alert.model.Severity;

public interface AlertsApi {

    /** 逃生口：脚本要传额外 fingerprint/labels/ttl 时直接 emit AlertSpec。 */
    void emit(AlertSpec spec);

    void emit(Severity severity, Map<String, String> labels, Map<String, String> annotations);

    /**
     * Groovy 简化 API（B9 / spec §5.5）：只接 annotations，labels 传 null——pipeline 维度打底下沉到
     * {@code DefaultAlertSink.persist}（从 {@code meta.labels} 与 {@code signal.labels} 合并），脚本无感。
     *
     * <p>{@code DefaultAlertsApi.toSignal} 把 null labels 归一化为 {@code Map.of()}，真正的打底合并发生在
     * sink；DryRunAlertsApi 不做打底（dry-run 没 pipeline labels 上下文），保留 null→empty 语义即可。
     */
    default void critical(final Map<String, Object> annotations) {
        emit(new AlertSpec(null, Severity.CRITICAL, null, annotations, false, null));
    }

    default void warning(final Map<String, Object> annotations) {
        emit(new AlertSpec(null, Severity.WARNING, null, annotations, false, null));
    }

    default void info(final Map<String, Object> annotations) {
        emit(new AlertSpec(null, Severity.INFO, null, annotations, false, null));
    }
}
