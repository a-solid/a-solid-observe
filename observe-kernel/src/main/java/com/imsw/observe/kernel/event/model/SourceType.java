package com.imsw.observe.kernel.event.model;

/**
 * 触发源类型。{@code UNKNOWN} 作为兜底（{@code ExecutionMeta.triggerType} 为 null 时，
 * {@code JpaExecutionRecorder} 写 {@code UNKNOWN.name()}），避免裸字符串逃逸枚举与 DB CHECK。
 */
public enum SourceType {
    CDC,
    CRON,
    API,
    UNKNOWN
}
