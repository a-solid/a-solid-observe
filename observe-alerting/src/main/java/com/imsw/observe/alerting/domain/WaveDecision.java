package com.imsw.observe.alerting.domain;

import java.time.Instant;

/**
 * {@link WavePolicy} 的波次决策结果——sealed，区分"续期现有波次"与"开新波次"两条落库路径。
 *
 * <ul>
 *   <li>{@link Extend}：当前 fingerprint 已有 ACTIVE 行 → 刷新 {@code ends_at}、写新 evidence。</li>
 *   <li>{@link Open}：无 ACTIVE 行（旧行已 EXPIRED 或首次）→ 插新 ACTIVE 行 + evidence。</li>
 * </ul>
 *
 * <p>"新波次 = 新行"规则由此显式表达（旧行 EXPIRED 后 findFirst...ACTIVE 返回空 → Open），
 * 取代过去散在 sink 里的隐式 if/else。
 */
public sealed interface WaveDecision permits WaveDecision.Extend, WaveDecision.Open {

    /** 新的波次到期时间（续期或开新都重算 {@code now + ttl}）。 */
    Instant newEndsAt();

    /** 续期现有活跃波次。 */
    record Extend(Long existingId, Instant newEndsAt) implements WaveDecision {}

    /** 开新波次（新 ACTIVE 行）。 */
    record Open(Instant newEndsAt) implements WaveDecision {}
}
