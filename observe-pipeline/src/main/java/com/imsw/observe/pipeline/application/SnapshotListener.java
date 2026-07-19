package com.imsw.observe.pipeline.application;

/**
 * Snapshot 变化观察者（ADR-0007：CronSource 作为 PipelineRegistry 的观察者）。
 *
 * <p>{@link PipelineRegistry#replace} 在 swap 新 snapshot 后调 {@link #onSnapshot}，把生效的 snapshot
 * 通知所有 listener。取代过去散在 bootstrap 的手动 {@code cronSource.sync(registry.snapshot())}——
 * "snapshot 变了要通知谁"成为 registry 的内联属性，不再靠调用方记。
 *
 * <p>实现方（如 CronSource）在 {@code onSnapshot} 内 diff 新旧订阅、起停各自的资源（cron 调度句柄）。
 * 实现抛 RuntimeException 不影响 swap（snapshot 已生效）也不影响其他 listener——registry 内部 per-listener
 * 容错。
 */
public interface SnapshotListener {

    void onSnapshot(PipelineRegistry.Snapshot snapshot);
}
