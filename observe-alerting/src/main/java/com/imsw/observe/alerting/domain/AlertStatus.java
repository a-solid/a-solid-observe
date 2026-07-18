package com.imsw.observe.alerting.domain;

/**
 * 告警状态机（ADR-0005）。
 *
 * <p>FIRING/RESOLVED 由系统驱动（wave 过期自动 resolve）；ACKNOWLEDGED/IGNORED 由用户驱动（ack/ignore 写接口）。
 * 合法转移见 {@code AlertDispositionService}。
 */
public enum AlertStatus {
    FIRING,
    RESOLVED,
    ACKNOWLEDGED,
    IGNORED
}
