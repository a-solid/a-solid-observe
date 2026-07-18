package com.imsw.observe.alerting.domain;

/**
 * Silence 匹配类型（ADR-0005 §3）。
 *
 * <ul>
 *   <li>FINGERPRINT —— 按告警指纹精确匹配</li>
 *   <li>LABELS —— 按标签子集匹配（告警 labels 包含 silence 的匹配标签）</li>
 *   <li>PIPELINE —— 按 namespace+pipeline 维度匹配</li>
 * </ul>
 */
public enum AlertSilenceMatchType {
    FINGERPRINT,
    LABELS,
    PIPELINE
}
