package com.imsw.observe.pipeline.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.imsw.observe.pipeline.domain.Pipeline;

/**
 * {@link PipelineRegistry} 的 snapshot 观察者通知（ADR-0007：CronSource 作为 registry 观察者）。
 *
 * <p>钉住：replace swap 后通知 listener（看到的是已生效 snapshot）；listener 抛异常不阻断 swap、不影响其他 listener。
 */
class PipelineRegistryTest {

    private static final class RecordingListener implements SnapshotListener {

        final List<PipelineRegistry.Snapshot> received = new ArrayList<>();

        @Override
        public void onSnapshot(final PipelineRegistry.Snapshot snapshot) {
            received.add(snapshot);
        }
    }

    /** 单元素 ObjectProvider stub（避免拉 Spring 上下文）。 */
    private static ObjectProvider<SnapshotListener> providerOf(final SnapshotListener... listeners) {
        return new ObjectProvider<>() {
            @Override
            public SnapshotListener getObject() {
                return listeners[0];
            }

            @Override
            public java.util.stream.Stream<SnapshotListener> orderedStream() {
                return java.util.Arrays.stream(listeners);
            }
        };
    }

    @Test
    void replaceNotifiesListenerWithAppliedSnapshot() {
        RecordingListener listener = new RecordingListener();
        PipelineRegistry registry = new PipelineRegistry(providerOf(listener));

        PipelineRegistry.Snapshot snap = PipelineRegistry.Snapshot.loaded(Map.of(), java.util.List.of());

        registry.replace(snap);

        assertThat(listener.received).containsExactly(snap);
        assertThat(registry.snapshot()).isSameAs(snap); // swap 已生效（listener 看到的是生效态）
    }

    @Test
    void listenerFailureDoesNotBlockSwapOrOtherListeners() {
        SnapshotListener bomber = snapshot -> {
            throw new IllegalStateException("boom");
        };
        RecordingListener survivor = new RecordingListener();
        PipelineRegistry registry = new PipelineRegistry(providerOf(bomber, survivor));

        PipelineRegistry.Snapshot snap = PipelineRegistry.Snapshot.loaded(Map.of(), java.util.List.of());

        registry.replace(snap); // bomber 抛异常被吞

        assertThat(registry.snapshot()).isSameAs(snap); // swap 仍生效
        assertThat(survivor.received).containsExactly(snap); // 其他 listener 仍被通知
    }

    @Test
    void noListenerConstructorSkipsNotifyGracefully() {
        // 无参构造器（测试/无 Spring）：replace 不爆炸、snapshot 正常换。
        PipelineRegistry registry = new PipelineRegistry();
        PipelineRegistry.Snapshot snap = PipelineRegistry.Snapshot.loaded(
                Map.of(
                        1L,
                        new Pipeline(
                                1L,
                                "ns",
                                1,
                                Map.of(),
                                "name",
                                Pipeline.Status.PUBLISHED,
                                java.util.List.of(),
                                java.time.Instant.now(),
                                java.time.Instant.now(),
                                0.0)),
                java.util.List.of());
        registry.replace(snap);
        assertThat(registry.isLoaded()).isTrue();
        assertThat(registry.snapshot().pipelineById(1L)).isNotNull();
    }
}
