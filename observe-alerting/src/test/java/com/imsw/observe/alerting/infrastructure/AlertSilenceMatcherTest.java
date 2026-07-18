package com.imsw.observe.alerting.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.imsw.observe.alerting.domain.AlertSilenceMatchType;
import com.imsw.observe.alerting.infrastructure.persistence.silence.AlertSilencePo;
import com.imsw.observe.alerting.infrastructure.persistence.silence.AlertSilenceRepository;

/**
 * B7：silence 匹配器（三种 matchType + 生效窗口）。
 */
class AlertSilenceMatcherTest {

    private final AlertSilenceRepository repository = mock(AlertSilenceRepository.class);

    private final AlertSilenceMatcher matcher = new AlertSilenceMatcher(repository, Duration.ofMillis(10_000));

    @Test
    void matchesFingerprint() {
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);
        when(repository.findByNamespaceAndEndsAtAfter(eq("ns"), any()))
                .thenReturn(List.of(
                        silence("ns", AlertSilenceMatchType.FINGERPRINT, Map.of("fingerprint", "fp-1"), start, end)));

        assertThat(matcher.silenced("ns", "fp-1", Map.of(), 1L)).isTrue();
        assertThat(matcher.silenced("ns", "fp-other", Map.of(), 1L)).isFalse();
    }

    @Test
    void matchesLabelsSubset() {
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);
        when(repository.findByNamespaceAndEndsAtAfter(eq("ns"), any()))
                .thenReturn(List.of(silence(
                        "ns", AlertSilenceMatchType.LABELS, Map.of("labels", Map.of("entity", "order")), start, end)));

        assertThat(matcher.silenced("ns", "fp", Map.of("entity", "order", "team", "x"), 1L))
                .isTrue();
        assertThat(matcher.silenced("ns", "fp", Map.of("entity", "payment"), 1L))
                .isFalse();
    }

    @Test
    void matchesPipeline() {
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);
        when(repository.findByNamespaceAndEndsAtAfter(eq("ns"), any()))
                .thenReturn(
                        List.of(silence("ns", AlertSilenceMatchType.PIPELINE, Map.of("pipelineId", 42), start, end)));

        assertThat(matcher.silenced("ns", "fp", Map.of(), 42L)).isTrue();
        assertThat(matcher.silenced("ns", "fp", Map.of(), 7L)).isFalse();
    }

    @Test
    void nullNamespaceNeverSilenced() {
        assertThat(matcher.silenced(null, "fp", Map.of(), 1L)).isFalse();
    }

    @Test
    void invalidateClearsCache() {
        Instant start = Instant.now().minusSeconds(60);
        Instant end = Instant.now().plusSeconds(60);
        when(repository.findByNamespaceAndEndsAtAfter(eq("ns"), any()))
                .thenReturn(List.of())
                .thenReturn(List.of(
                        silence("ns", AlertSilenceMatchType.FINGERPRINT, Map.of("fingerprint", "fp-1"), start, end)));

        assertThat(matcher.silenced("ns", "fp-1", Map.of(), 1L)).isFalse();
        matcher.invalidate("ns");
        // 失效后第二次查返回命中规则
        assertThat(matcher.silenced("ns", "fp-1", Map.of(), 1L)).isTrue();
    }

    private static AlertSilencePo silence(
            final String namespace,
            final AlertSilenceMatchType type,
            final Map<String, Object> match,
            final Instant startsAt,
            final Instant endsAt) {
        AlertSilencePo po = new AlertSilencePo();
        po.id = System.nanoTime();
        po.namespace = namespace;
        po.matchType = type.name();
        po.match = match;
        po.startsAt = startsAt;
        po.endsAt = endsAt;
        po.createdBy = "tester";
        po.createdAt = startsAt;
        po.updatedAt = startsAt;
        return po;
    }
}
