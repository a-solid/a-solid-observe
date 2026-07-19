package com.imsw.observe.kernel.alert.model;

import java.time.Duration;
import java.util.Map;

/**
 * 脚本 emit 的一条告警信号。
 *
 * @param fingerprint   去重键；脚本可显式传，否则由 sink 按 pipelineId + labels 计算
 * @param severity      CRITICAL/WARNING/INFO
 * @param labels        脚本侧 labels（覆盖 pipeline labels，sink 合并）；简化 API 传 null→空
 * @param annotations   告警注解（给人/oncall 看，进 alert 表）
 * @param shortCircuit  emit 后是否短路（结束 pipeline）
 * @param ttl           波次 TTL 覆盖；null 走 severity 默认（C30/W10/I5）
 */
public record AlertSignal(
        String fingerprint,
        Severity severity,
        Map<String, String> labels,
        Map<String, Object> annotations,
        boolean shortCircuit,
        Duration ttl) {}
