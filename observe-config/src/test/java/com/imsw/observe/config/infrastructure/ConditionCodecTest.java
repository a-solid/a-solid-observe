package com.imsw.observe.config.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.imsw.observe.pipeline.domain.subscription.Condition;

class ConditionCodecTest {

    private final ConditionCodec codec = new ConditionCodec();

    @Test
    void roundTripCompare() {
        Condition original = new Condition.Compare("after.status", Condition.Compare.Op.EQ, "PAID");
        String json = codec.toJson(original);
        Condition parsed = codec.fromJson(json);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void roundTripAndWithIn() {
        Condition original = new Condition.And(List.of(
                new Condition.Compare("after.amount", Condition.Compare.Op.GT, new java.math.BigDecimal("1000")),
                new Condition.In("meta.db", Set.of("trade_db", "risk_db"))));
        String json = codec.toJson(original);
        Condition parsed = codec.fromJson(json);
        assertThat(parsed).isEqualTo(original);
    }

    @Test
    void nullRoundTripsToNull() {
        assertThat(codec.toJson(null)).isNull();
        assertThat(codec.fromJson(null)).isNull();
        assertThat(codec.fromJson("")).isNull();
    }
}
