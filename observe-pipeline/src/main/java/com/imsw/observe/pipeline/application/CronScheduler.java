package com.imsw.observe.pipeline.application;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;

import com.imsw.observe.kernel.event.model.Event;
import com.imsw.observe.kernel.event.model.SourceType;
import com.imsw.observe.kernel.event.model.TickEvent;
import com.imsw.observe.kernel.event.model.TickMeta;
import com.imsw.observe.pipeline.application.PipelineRegistry.Snapshot;
import com.imsw.observe.pipeline.domain.subscription.Subscription;
import com.imsw.observe.pipeline.domain.subscription.Subscription.SourceRef.Concurrent;

/**
 * Cron per-subscription 调度器（B4，ADR-0007）。
 *
 * <p>有状态组件：作为 {@link PipelineRegistry} 的观察者。每次 {@link #sync(Snapshot)} 与当前跟踪的
 * CRON 订阅做 diff——新增/变更（{@code cronExpression}/{@code cronName}/{@code concurrent} 变化）→
 * 取消旧句柄 + 起新调度；删除（订阅消失或不再 CRON）→ 取消。每条 CRON 订阅一个调度句柄
 * （调度单元 = 订阅；相同表达式也是独立调度）。
 *
 * <p><b>自递归调度</b>：cron 是非固定间隔，不能用 {@code scheduleAtFixedRate}。每次到点算下一发时刻，
 * 用 {@code scheduler.schedule(..., delay, MS)} 投递；fire 末尾（未取消时）再次 {@code scheduleNext}，
 * 形成调度链。{@link CronExpression#next(Temporal)} 给出下一发的未来时刻——天然实现 misfire M1
 * （重启不补跑，只看未来）。
 *
 * <p><b>并发 SKIP</b>：每订阅一个 {@link AtomicBoolean} running 标志。SKIP 时 {@code compareAndSet(false, true)}
 * 失败即跳过本次（串行不堆积）；ALLOW 时跳过 CAS 直接投递。{@code concurrent == null} 视为 SKIP
 * （Task 1 推迟默认）。
 *
 * <p>到点产出 {@link TickEvent}（{@link TickMeta#source()} = {@code "cron:" + cronName}，含
 * {@code cronName}/{@code cronExpression}）投给 {@link EventListener#onBatch(List)}（运行时 =
 * {@code SourceDispatcher::onBatch}），由 matcher 按 cron name 路由到订阅了该 cron 的 pipeline。
 *
 * <p>本类属 application 层：只依赖 application 端口 {@link EventListener} + kernel Event。
 * 无 infrastructure 具体导入（{@link ScheduledExecutorService} /
 * {@link CronExpression} 为 JDK/Spring 通用并发与表达式工具，非领域 infrastructure）。
 */
public final class CronScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CronScheduler.class);

    private final ScheduledExecutorService scheduler;

    private final EventListener listener;

    /** key = subscription id；value = 当前投递在 SES 上的调度句柄（每 fire 周期更新）。 */
    private final ConcurrentMap<Long, ScheduledFuture<?>> bySubscriptionId = new ConcurrentHashMap<>();

    /** key = subscription id；value = per-sub running 标志（SKIP 并发控制）。 */
    private final ConcurrentMap<Long, AtomicBoolean> runningFlags = new ConcurrentHashMap<>();

    /** key = subscription id；value = 已跟踪的 CronSub（diff 增量更新的依据）。 */
    private final ConcurrentMap<Long, CronSub> tracked = new ConcurrentHashMap<>();

    /** 标记 shutdown 已调用，避免 shutdown 后再 sync/schedule。 */
    private volatile boolean shutdown = false;

    public CronScheduler(final ScheduledExecutorService scheduler, final EventListener listener) {
        this.scheduler = scheduler;
        this.listener = listener;
    }

    /**
     * 与快照对齐 CRON 订阅调度。幂等：相同快照重复调用为 no-op。
     *
     * <p>遍历快照的 {@link Snapshot#subscriptionsBySource()}（按 source name 索引，含 CRON + API），
     * 过滤出 {@code sourceType == CRON} 的订阅。对每条 CRON 订阅：新（id 未跟踪）或变更
     * （{@code cronExpression}/{@code cronName}/{@code concurrent}/{@code source} 与跟踪值不同）→
     * cancel 旧句柄 + schedule 新。对已跟踪但快照中不再出现的（删除或转非 CRON）→ cancel + 清理。
     */
    public void sync(final Snapshot snapshot) {
        if (shutdown) {
            return;
        }
        Snapshot snap = snapshot == null ? Snapshot.empty() : snapshot;
        Map<Long, CronSub> nextById = collectCronSubs(snap);
        upsert(nextById);
        removeStale(nextById);
    }

    /** 收集快照中全部 {@code sourceType == CRON} 且表达式非空的订阅，key by id。 */
    private Map<Long, CronSub> collectCronSubs(final Snapshot snap) {
        Map<Long, CronSub> nextById = new java.util.HashMap<>();
        for (List<Subscription> group : snap.subscriptionsBySource.values()) {
            for (Subscription sub : group) {
                if (!isCron(sub)) {
                    continue;
                }
                CronSub cronSub = CronSub.from(sub);
                if (cronSub.cronExpression == null || cronSub.cronExpression.isBlank()) {
                    // 无表达式的 CRON 订阅无法调度——保护性跳过（加载层应已校验，此处兜底）。
                    LOG.warn("skip CRON subscription {}: blank cronExpression", sub.id());
                    continue;
                }
                nextById.put(sub.id(), cronSub);
            }
        }
        return nextById;
    }

    private static boolean isCron(final Subscription sub) {
        return sub.source() != null && sub.source().sourceType() == SourceType.CRON;
    }

    /** 新增（id 未跟踪）或变更（字段不同）→ cancel 旧 + schedule 新。 */
    private void upsert(final Map<Long, CronSub> nextById) {
        for (Map.Entry<Long, CronSub> e : nextById.entrySet()) {
            Long id = e.getKey();
            CronSub next = e.getValue();
            CronSub prev = tracked.get(id);
            if (prev == null) {
                scheduleNew(id, next);
            } else if (!prev.equals(next)) {
                LOG.info("CRON subscription {} changed, reschedule: was={}, now={}", id, prev, next);
                cancel(id);
                scheduleNew(id, next);
            }
        }
    }

    /** tracked 中存在但本轮快照缺失（删除或转非 CRON）→ cancel。 */
    private void removeStale(final Map<Long, CronSub> nextById) {
        for (Long id : tracked.keySet()) {
            if (!nextById.containsKey(id)) {
                cancel(id);
            }
        }
    }

    private void scheduleNew(final Long id, final CronSub sub) {
        runningFlags.put(id, new AtomicBoolean(false));
        tracked.put(id, sub);
        scheduleNext(id, sub);
        LOG.info(
                "CRON subscription {} scheduled: expr='{}', name='{}', concurrent={}",
                id,
                sub.cronExpression,
                sub.cronName,
                sub.concurrent);
    }

    /**
     * 自递归调度一环：算下一发时刻，schedule 投递，句柄存入 map（每周期覆盖）。
     *
     * <p>{@link CronExpression#next(Temporal)} 返回严格大于 now 的下一时刻（M1 misfire：重启只看未来）。
     * 用 {@link ZonedDateTime}（非裸 {@link Instant}）传入——cron 含 DayOfWeek/宏（L/W/#）字段，
     * 裸 Instant 会抛 UnsupportedTemporalTypeException。
     */
    private void scheduleNext(final Long id, final CronSub sub) {
        if (shutdown) {
            return;
        }
        if (!tracked.containsKey(id)) {
            // 已被 cancel / 移除——不再 re-arm（fire 末尾调本方法的防御性检查）。
            return;
        }
        try {
            CronExpression cron = CronExpression.parse(sub.cronExpression);
            ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
            ZonedDateTime nextFire = cron.next(now);
            if (nextFire == null) {
                // 表达式无未来触发（罕见，如静态历史时刻）——终止调度链。
                LOG.warn("CRON subscription {} has no future fire after {}; stop chain", id, now);
                return;
            }
            long delayMs = Math.max(0L, Duration.between(now, nextFire).toMillis());
            ScheduledFuture<?> future = scheduler.schedule(() -> fire(id, sub), delayMs, TimeUnit.MILLISECONDS);
            bySubscriptionId.put(id, future);
        } catch (RuntimeException e) {
            // 表达式非法等——终止该订阅调度链，不污染全局。
            LOG.error("CRON subscription {} schedule failed, stop chain: expr='{}'", id, sub.cronExpression, e);
            bySubscriptionId.remove(id);
        }
    }

    /**
     * 到点 fire：SKIP 并发 CAS、构造 TickEvent 投 listener、finally reset running 标志 + re-arm（未取消时）。
     */
    void fire(final Long id, final CronSub sub) {
        if (shutdown || !tracked.containsKey(id)) {
            // 已 shutdown 或已被 cancel——不再投递。
            return;
        }
        AtomicBoolean running = runningFlags.get(id);
        if (!tryAcquire(sub, running)) {
            // SKIP 且 CAS 失败——本次跳过，但 re-arm 保持调度链不断。
            LOG.info("CRON subscription {} fire skipped (previous run in progress, SKIP)", id);
            rearm(id, sub);
            return;
        }
        try {
            dispatch(id, sub);
        } finally {
            if (running != null && isSkipMode(sub.concurrent)) {
                running.set(false);
            }
            rearm(id, sub);
        }
    }

    /** SKIP：CAS(false→true) 失败返回 false；ALLOW：直接放行。 */
    private static boolean tryAcquire(final CronSub sub, final AtomicBoolean running) {
        if (!isSkipMode(sub.concurrent)) {
            return true;
        }
        return running != null && running.compareAndSet(false, true);
    }

    private static boolean isSkipMode(final Concurrent policy) {
        return policy == Concurrent.SKIP || policy == null;
    }

    private void dispatch(final Long id, final CronSub sub) {
        TickMeta meta = new TickMeta(
                "cron:" + (sub.cronName != null ? sub.cronName : sub.source),
                sub.cronName,
                sub.cronExpression,
                Map.of());
        Event event = new TickEvent(meta, Instant.now());
        try {
            listener.onBatch(List.of(event));
        } catch (RuntimeException e) {
            // 下游失败不影响 re-arm（at-least-once 由下游负责；本类保证下一发仍调度）。
            LOG.warn("CRON subscription {} listener.onBatch failed", id, e);
        }
    }

    private void rearm(final Long id, final CronSub sub) {
        if (shutdown) {
            return;
        }
        if (!tracked.containsKey(id)) {
            // 中途被 cancel——终止调度链。
            return;
        }
        scheduleNext(id, sub);
    }

    private void cancel(final Long id) {
        ScheduledFuture<?> f = bySubscriptionId.remove(id);
        if (f != null) {
            f.cancel(false);
        }
        runningFlags.remove(id);
        tracked.remove(id);
        LOG.info("CRON subscription {} cancelled", id);
    }

    /**
     * 关闭：取消全部句柄、清空状态、shutdown SES。
     *
     * <p>由调用方（Task 3 装配）作为 bean destroyMethod 调用；本类自测中由测试驱动。
     */
    public void shutdown() {
        shutdown = true;
        for (ScheduledFuture<?> f : bySubscriptionId.values()) {
            f.cancel(false);
        }
        bySubscriptionId.clear();
        runningFlags.clear();
        tracked.clear();
        scheduler.shutdown();
        LOG.info("CronScheduler shutdown");
    }

    /** 跟踪的 CRON 订阅数（自测/可观测）。 */
    int trackedCount() {
        return tracked.size();
    }

    /**
     * Cron 订阅的可比较快照（diff 依据）。{@code source} 作为 fallback 用于 TickMeta.source
     * （cronName 为 null 时）。
     */
    static final class CronSub {

        final String source;

        final String cronName;

        final String cronExpression;

        final Concurrent concurrent;

        private CronSub(
                final String source, final String cronName, final String cronExpression, final Concurrent concurrent) {
            this.source = source;
            this.cronName = cronName;
            this.cronExpression = cronExpression;
            this.concurrent = concurrent;
        }

        static CronSub from(final Subscription sub) {
            Subscription.SourceRef s = sub.source();
            return new CronSub(s.mq(), s.cronName(), s.cronExpression(), s.concurrent());
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof CronSub other)) {
                return false;
            }
            return java.util.Objects.equals(source, other.source)
                    && java.util.Objects.equals(cronName, other.cronName)
                    && java.util.Objects.equals(cronExpression, other.cronExpression)
                    && concurrent == other.concurrent;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(source, cronName, cronExpression, concurrent);
        }

        @Override
        public String toString() {
            return "CronSub{source='"
                    + source + "', cronName='" + cronName + "', expr='" + cronExpression
                    + "', concurrent=" + concurrent + '}';
        }
    }
}
