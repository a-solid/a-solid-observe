package com.imsw.observe.kernel.event.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@link Event#sourceType()} 契约测试（ADR-0006：sourceType 隐含于子类型）。
 *
 * <p>钉住每个 sealed 子类型返回的固定 {@link SourceType}，含 {@link DelayedEvent} 的递归语义
 * （延时重放非独立来源 → 取 originalEvent 的 sourceType）。这是新加的领域事实，必须有测试固化，
 * 防止未来子类型误实现或 DelayedEvent 递归语义被改坏。
 */
class EventSubtypeSourceTypeTest {

    @Test
    void cdcReturnsCdc() {
        assertThat(cdcEvent().sourceType()).isEqualTo(SourceType.CDC);
    }

    @Test
    void tickReturnsCron() {
        assertThat(tickEvent().sourceType()).isEqualTo(SourceType.CRON);
    }

    @Test
    void apiReturnsApi() {
        assertThat(apiEvent().sourceType()).isEqualTo(SourceType.API);
    }

    @Test
    void delayedWrappingCdcReturnsCdc() {
        assertThat(delayed(cdcEvent()).sourceType()).isEqualTo(SourceType.CDC);
    }

    @Test
    void delayedWrappingApiReturnsApi() {
        assertThat(delayed(apiEvent()).sourceType()).isEqualTo(SourceType.API);
    }

    @Test
    void nestedDelayedRecursesToOriginal() {
        // delayed(delayed(cdc)) → 递归两层 → CDC
        assertThat(delayed(delayed(cdcEvent())).sourceType()).isEqualTo(SourceType.CDC);
    }

    private static Event cdcEvent() {
        return new CdcEvent(
                new CdcMeta("ibm-mq", "trade", "orders", Map.of()), Map.of(), Map.of(), CdcOp.INSERT, Instant.now());
    }

    private static Event tickEvent() {
        return new TickEvent(new TickMeta(7L, "0 0 * * * *", Instant.now(), Map.of()), Instant.now());
    }

    private static Event apiEvent() {
        return new ApiEvent(new ApiMeta(9L, Map.of()), Map.of(), Instant.now());
    }

    private static DelayedEvent delayed(final Event original) {
        return new DelayedEvent(new DelayedMeta(42L, "k"), original, Instant.now());
    }
}
