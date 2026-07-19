package com.imsw.observe.alerting.domain;

/**
 * 告警系统态（ADR-0005 两维分离后）：时间驱动，与用户处置维度 {@link AlertDisposition} 正交。
 *
 * <ul>
 *   <li>{@code ACTIVE}：波次活跃中（仍在收敛/去重，{@code ends_at} 未到）。</li>
 *   <li>{@code EXPIRED}：波次到期（{@code ends_at < now}，{@code AlertResolveJob} 自动翻）。
 *       <b>注意</b>：本项目是事件驱动 + TTL 模型，{@code EXPIRED} 表示"波次时间窗结束"，
 *       <b>非</b>业界（Alertmanager/Grafana）那种"触发条件恢复"。问题未必解决——下次同
 *       fingerprint emit 会开新波次 = 新 ACTIVE 行。故不用 {@code RESOLVED}（会误导为"问题解决"）。</li>
 * </ul>
 *
 * <p>用户处置（ack/ignore）不再混入此枚举——见 {@link AlertDisposition}。
 */
public enum AlertStatus {
    ACTIVE,
    EXPIRED
}
