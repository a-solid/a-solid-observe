package com.imsw.observe.pipeline.domain.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;

class ConditionTest {

    @Test
    void compareEqMatchesAfterField() {
        Event event = event(Map.of("status", "PAID"));
        Condition c = new Condition.Compare("after.status", Condition.Compare.Op.EQ, "PAID");
        assertThat(c.matches(event)).isTrue();
    }

    @Test
    void compareEqNormalizesNumericString() {
        Event event = event(Map.of("amount", 2000L));
        Condition c = new Condition.Compare("after.amount", Condition.Compare.Op.GT, "1000");
        assertThat(c.matches(event)).isTrue();
    }

    @Test
    void compareLtOnBigDecimal() {
        Event event = event(Map.of("amount", new BigDecimal("999.5")));
        Condition c = new Condition.Compare("after.amount", Condition.Compare.Op.LT, 1000);
        assertThat(c.matches(event)).isTrue();
    }

    @Test
    void neOnMissingField() {
        Event event = event(Map.of());
        Condition c = new Condition.Compare("after.status", Condition.Compare.Op.NE, "PAID");
        assertThat(c.matches(event)).isTrue();
    }

    @Test
    void opFieldMatchesOpName() {
        Event event = event(Map.of());
        Condition c = new Condition.Compare("op", Condition.Compare.Op.EQ, "INSERT");
        assertThat(c.matches(event)).isTrue();
    }

    @Test
    void inMatchesAnyValue() {
        Event event = event(Map.of("db_region", "east"));
        Condition c = new Condition.In("after.db_region", Set.of("east", "west"));
        assertThat(c.matches(event)).isTrue();
    }

    @Test
    void andOrCompose() {
        Event event = event(Map.of("status", "PAID", "amount", 500));
        Condition c = new Condition.And(List.of(
                new Condition.Compare("after.status", Condition.Compare.Op.EQ, "PAID"),
                new Condition.Or(List.of(
                        new Condition.Compare("after.amount", Condition.Compare.Op.GT, 1000),
                        new Condition.Compare("after.amount", Condition.Compare.Op.GT, 400)))));
        assertThat(c.matches(event)).isTrue();
    }

    @Test
    void missingFieldIsFalseForOrdered() {
        Event event = event(Map.of());
        Condition c = new Condition.Compare("after.amount", Condition.Compare.Op.GT, 1);
        assertThat(c.matches(event)).isFalse();
    }

    private Event event(final Map<String, Object> after) {
        Event.EventMeta meta = new Event.EventMeta(SourceType.CDC, "mq", "trade_db", "orders", Map.of());
        return new Event(meta, Map.of(), after, Op.INSERT, java.time.Instant.now());
    }
}
