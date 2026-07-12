package com.imsw.observe.bootstrap.demo;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.Op;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.pipeline.domain.ErrorPolicy;
import com.imsw.observe.pipeline.domain.NodeSpec;
import com.imsw.observe.pipeline.domain.Pipeline;

public final class DemoPipelineFactory {

    private DemoPipelineFactory() {}

    public static Pipeline buildPipeline() {
        NodeSpec compute = new NodeSpec(
                "compute",
                """
                def raw = event.after.get("amount")
                def amt = raw as BigDecimal
                ctx.set("amt", amt)
                return false
                """,
                ErrorPolicy.FAIL,
                Set.of("amt"),
                Set.of());
        NodeSpec check = new NodeSpec(
                "check",
                """
                def amt = ctx.get("amt", BigDecimal)
                def limit = 10000
                if (amt != null && amt > limit) {
                    alerts.emit(
                        com.imsw.observe.kernel.alert.model.Severity.CRITICAL,
                        java.util.Map.of("entity", "order", "team", "demo"),
                        java.util.Map.of("summary", "fraud amt=" + amt.toString()),
                        new com.imsw.observe.kernel.alert.model.AlertSignal.EvidenceSpec(
                            java.util.List.of(),
                            true,
                            true
                        )
                    )
                    return true
                }
                return false
                """,
                ErrorPolicy.FAIL,
                Set.of(),
                Set.of("amt"));
        return new Pipeline(
                "demo-pipeline",
                1,
                "demo-team",
                "demo-app",
                Map.of("domain", "trade"),
                "Demo Pipeline",
                Pipeline.Status.PUBLISHED,
                List.of(compute, check),
                Instant.now(),
                Instant.now(),
                1.0);
    }

    public static Event mockEvent() {
        Event.EventMeta meta = new Event.EventMeta(SourceType.CDC, "demo-mq", "test_db", "orders", Map.of());
        return new Event(meta, Map.of(), Map.of("amount", 2000L), Op.INSERT, Instant.now());
    }
}
