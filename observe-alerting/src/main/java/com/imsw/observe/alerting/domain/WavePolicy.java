package com.imsw.observe.alerting.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.alert.model.Severity;

/**
 * 波次动力学策略（ADR-0005）——纯领域函数，零 JPA 依赖。
 *
 * <p>把过去散在 {@code DefaultAlertSink}（dedup/续期/开新）+ {@code AlertResolveJob}（到期）的波次规则
 * 收敛到此，与落库（JPA）分离：本类只算<b>决策</b>（续期还是开新、{@code ends_at} 多少、是否到期），
 * 调用方（sink / resolveJob）拿决策结果去落库。由此波次规则可纯单测（无需 {@code @SpringBootTest} + H2）。
 *
 * <p>波次规则：
 * <ul>
 *   <li>emit 进来 → 按 fingerprint 找当前 ACTIVE 行：有 → {@link WaveDecision.Extend}（续期 ends_at、写新 evidence）；
 *       无 → {@link WaveDecision.Open}（插新 ACTIVE 行 + evidence）。"新波次=新行"由 findFirst...ACTIVE
 *       在旧行 EXPIRED 后返回空自然落进 Open。</li>
 *   <li>TTL：{@code signal.ttl()} 优先，否则按 severity 默认（C30/W10/I5，{@link #defaultTtl}）。</li>
 *   <li>到期：{@code ends_at < now}（{@link #isExpired}）。</li>
 * </ul>
 */
public final class WavePolicy {

    /** 默认波次 TTL（ADR-0005 §1）：CRITICAL 30min / WARNING 10min / INFO 5min。 */
    public Duration defaultTtl(final Severity severity) {
        return switch (severity) {
            case CRITICAL -> Duration.ofMinutes(30);
            case WARNING -> Duration.ofMinutes(10);
            case INFO -> Duration.ofMinutes(5);
        };
    }

    /**
     * 判定一次 emit 是续期现有波次还是开新波次。
     *
     * @param existing 当前 fingerprint 的 ACTIVE 行（无则 Optional.empty()）
     * @param signal   本次 emit 的告警信号（提供 ttl/severity）
     * @param now      本次 emit 时刻
     * @return {@link WaveDecision.Extend}（续期）或 {@link WaveDecision.Open}（开新）
     */
    public WaveDecision decide(final Optional<ActiveWave> existing, final AlertSignal signal, final Instant now) {
        Duration ttl = signal.ttl() != null ? signal.ttl() : defaultTtl(signal.severity());
        Instant newEndsAt = now.plus(ttl);
        return existing.<WaveDecision>map(w -> new WaveDecision.Extend(w.id(), newEndsAt))
                .orElseGet(() -> new WaveDecision.Open(newEndsAt));
    }

    /** 波次是否到期（{@code ends_at < now}）。{@code ends_at} 为 null 视为未到期。 */
    public boolean isExpired(final Instant endsAt, final Instant now) {
        return endsAt != null && endsAt.isBefore(now);
    }
}
