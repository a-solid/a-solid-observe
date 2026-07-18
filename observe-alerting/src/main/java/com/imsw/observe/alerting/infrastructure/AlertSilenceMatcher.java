package com.imsw.observe.alerting.infrastructure;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.imsw.observe.alerting.domain.AlertSilenceEntity;
import com.imsw.observe.alerting.infrastructure.persistence.silence.AlertSilenceRepository;

/**
 * Silence 匹配器（ADR-0005 §3）：emit 热路径前查询是否被静默。
 *
 * <p>内存缓存按 namespace 暂存活跃规则，短 TTL 刷新（{@code observe.alerting.silence.cache-ttl-millis}，默认 10s），
 * 避免每次 emit 查库。{@code invalidate(namespace)} 在 CRUD 后主动失效。
 */
public class AlertSilenceMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(AlertSilenceMatcher.class);

    private final AlertSilenceRepository repository;

    private final Duration ttl;

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public AlertSilenceMatcher(final AlertSilenceRepository repository, final Duration ttl) {
        this.repository = repository;
        this.ttl = ttl;
    }

    /** 命中任意生效中的 silence 规则则返回 true（emit 应被静默，不建告警）。 */
    public boolean silenced(
            final String namespace, final String fingerprint, final Map<String, String> labels, final Long pipelineId) {
        if (namespace == null) {
            return false;
        }
        Instant now = Instant.now();
        for (AlertSilenceEntity rule : activeRules(namespace, now)) {
            if (now.isBefore(rule.startsAt()) || !now.isBefore(rule.endsAt())) {
                continue;
            }
            if (matches(rule, fingerprint, labels, pipelineId)) {
                LOG.info(
                        "alert silenced namespace={} fingerprint={} matchType={}",
                        namespace,
                        fingerprint,
                        rule.matchType());
                return true;
            }
        }
        return false;
    }

    /** CRUD 后主动失效某 namespace 缓存。 */
    public void invalidate(final String namespace) {
        cache.remove(namespace);
    }

    private List<AlertSilenceEntity> activeRules(final String namespace, final Instant now) {
        Cached cached = cache.get(namespace);
        if (cached != null && cached.loadedAt.plus(ttl).isAfter(now)) {
            return cached.rules;
        }
        List<AlertSilenceEntity> rules = repository.findByNamespaceAndEndsAtAfter(namespace, now).stream()
                .map(com.imsw.observe.alerting.infrastructure.persistence.silence.AlertSilenceMapper::toEntity)
                .toList();
        cache.put(namespace, new Cached(rules, now));
        return rules;
    }

    private static boolean matches(
            final AlertSilenceEntity rule,
            final String fingerprint,
            final Map<String, String> labels,
            final Long pipelineId) {
        Map<String, Object> match = rule.match() == null ? Map.of() : rule.match();
        return switch (rule.matchType()) {
            case FINGERPRINT -> matchesFingerprint(match, fingerprint);
            case LABELS -> matchesLabels(match, labels);
            case PIPELINE -> matchesPipeline(match, pipelineId);
        };
    }

    private static boolean matchesFingerprint(final Map<String, Object> match, final String fingerprint) {
        Object fp = match.get("fingerprint");
        return fp != null && fp.toString().equals(fingerprint);
    }

    private static boolean matchesLabels(final Map<String, Object> match, final Map<String, String> labels) {
        Object wanted = match.get("labels");
        if (!(wanted instanceof Map<?, ?> wantedMap) || wantedMap.isEmpty()) {
            return false;
        }
        for (Map.Entry<?, ?> e : wantedMap.entrySet()) {
            String key = String.valueOf(e.getKey());
            String val = e.getValue() == null ? null : String.valueOf(e.getValue());
            if (!val.equals(labels.get(key))) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesPipeline(final Map<String, Object> match, final Long pipelineId) {
        Object pid = match.get("pipelineId");
        return pid != null && pipelineId != null && Long.valueOf(pid.toString()).equals(pipelineId);
    }

    private record Cached(List<AlertSilenceEntity> rules, Instant loadedAt) {}
}
