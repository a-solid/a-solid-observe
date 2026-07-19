package com.imsw.observe.alerting.domain;

import java.time.Instant;

/**
 * 波次决策的最小输入——{@link WavePolicy} 仅需"现有活跃波次的 id + ends_at"即可判定续期/开新。
 * 抽成 domain 值，避免 {@code WavePolicy} 依赖 {@code AlertPo}（infrastructure）。
 *
 * @param id     现有 ACTIVE 告警行 id（续期时回写用）
 * @param endsAt 现有波次到期时间
 */
public record ActiveWave(Long id, Instant endsAt) {}
