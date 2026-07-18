package com.imsw.observe.pipeline.domain.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.imsw.observe.kernel.event.model.CdcEvent;
import com.imsw.observe.kernel.event.model.CdcMeta;
import com.imsw.observe.kernel.event.model.CdcOp;
import com.imsw.observe.kernel.event.model.Event;

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

    @Test
    void cdcMetaPathResolves() {
        Event event = event(Map.of());
        Condition onDb = new Condition.Compare("meta.db", Condition.Compare.Op.EQ, "trade_db");
        Condition onTable = new Condition.Compare("meta.table", Condition.Compare.Op.EQ, "orders");
        Condition onSource = new Condition.Compare("meta.source", Condition.Compare.Op.EQ, "mq");
        assertThat(onDb.matches(event)).isTrue();
        assertThat(onTable.matches(event)).isTrue();
        assertThat(onSource.matches(event)).isTrue();
    }

    private Event event(final Map<String, Object> after) {
        CdcMeta meta = new CdcMeta("mq", "trade_db", "orders", Map.of());
        return new CdcEvent(meta, Map.of(), after, CdcOp.INSERT, Instant.now());
    }
}
