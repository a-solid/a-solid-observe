package com.imsw.observe.alerting.domain;

/**
 * 告警用户处置维度（ADR-0005 两维分离）：人驱动，与系统态 {@link AlertStatus} 完全正交。
 *
 * <ul>
 *   <li>{@code NONE}：未处置（默认）。</li>
 *   <li>{@code ACKNOWLEDGED}：用户确认"已知/在处理"，带备注 + 操作人 + 时间。不代表问题消失。</li>
 *   <li>{@code IGNORED}：用户主动忽略<b>单条</b>告警，带备注。区别于 SILENCE（作用于未来同类）。</li>
 * </ul>
 *
 * <p><b>正交语义</b>：disposition 独立于 status——ack/ignore 可对任意 status（ACTIVE 或 EXPIRED）的行生效，
 * 仅打戳、不改变 status。EXPIRED 行也能 ack（表达"事后我看到了"，本项目 EXPIRED 非条件恢复，
 * 问题未必解决，事后标记有意义）。ack/ignore 不阻止系统到期（ACTIVE 到期照常 → EXPIRED）。
 *
 * <p><b>不设用户手动 close</b>：要"让告警消失"靠 silence（作用于未来同类）或等到期，与业界
 * （Alertmanager/Grafana 都无手动 close）一致。
 */
public enum AlertDisposition {
    NONE,
    ACKNOWLEDGED,
    IGNORED
}
