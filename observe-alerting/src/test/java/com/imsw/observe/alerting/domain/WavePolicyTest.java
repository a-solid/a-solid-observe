package com.imsw.observe.alerting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.alert.model.AlertSignal;
import com.imsw.observe.kernel.alert.model.Severity;

/**
 * {@link WavePolicy} 纯函数测试（ADR-0005）——无需 JPA/Spring，纯领域逻辑单测。
 * 钉住：续期→Extend、开新→Open、ttl 默认（C30/W10/I5）、ttl 覆盖、isExpired 边界。
 */
class WavePolicyTest {

    private final WavePolicy policy = new WavePolicy();

    @Test
    void existingActiveWaveExtends() {
        Instant now = Instant.parse("2026-07-19T10:00:00Z");
        Instant existingEnds = now.plusSeconds(60);
        AlertSignal signal = signal(Severity.CRITICAL, null);

        WaveDecision decision = policy.decide(Optional.of(new ActiveWave(42L, existingEnds)), signal, now);

        assertThat(decision).isInstanceOf(WaveDecision.Extend.class);
        WaveDecision.Extend extend = (WaveDecision.Extend) decision;
        assertThat(extend.existingId()).isEqualTo(42L);
        // 默认 CRITICAL ttl=30min → new ends = now + 30min
        assertThat(extend.newEndsAt()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void noActiveWaveOpensNew() {
        Instant now = Instant.parse("2026-07-19T10:00:00Z");
        AlertSignal signal = signal(Severity.WARNING, null);

        WaveDecision decision = policy.decide(Optional.empty(), signal, now);

        assertThat(decision).isInstanceOf(WaveDecision.Open.class);
        // 默认 WARNING ttl=10min
        assertThat(((WaveDecision.Open) decision).newEndsAt()).isEqualTo(now.plus(Duration.ofMinutes(10)));
    }

    @Test
    void signalTtlOverridesSeverityDefault() {
        Instant now = Instant.parse("2026-07-19T10:00:00Z");
        AlertSignal signal = signal(Severity.CRITICAL, Duration.ofSeconds(90)); // 覆盖默认 30min

        WaveDecision decision = policy.decide(Optional.empty(), signal, now);

        assertThat(((WaveDecision.Open) decision).newEndsAt()).isEqualTo(now.plusSeconds(90));
    }

    @Test
    void defaultTtlPerSeverity() {
        assertThat(policy.defaultTtl(Severity.CRITICAL)).isEqualTo(Duration.ofMinutes(30));
        assertThat(policy.defaultTtl(Severity.WARNING)).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.defaultTtl(Severity.INFO)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void isExpiredTrueWhenEndsBeforeNow() {
        Instant now = Instant.parse("2026-07-19T10:00:00Z");
        assertThat(policy.isExpired(now.minusSeconds(1), now)).isTrue();
    }

    @Test
    void isExpiredFalseWhenEndsAfterNow() {
        Instant now = Instant.parse("2026-07-19T10:00:00Z");
        assertThat(policy.isExpired(now.plusSeconds(1), now)).isFalse();
    }

    @Test
    void isExpiredFalseForNullEndsAt() {
        assertThat(policy.isExpired(null, Instant.now())).isFalse();
    }

    private static AlertSignal signal(final Severity severity, final Duration ttl) {
        return new AlertSignal("fp", severity, java.util.Map.of(), java.util.Map.of(), false, ttl);
    }
}
