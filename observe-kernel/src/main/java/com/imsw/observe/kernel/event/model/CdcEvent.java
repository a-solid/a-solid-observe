package com.imsw.observe.kernel.event.model;

import java.time.Instant;
import java.util.Map;

/**
 * CDC 数据变更事件。由 {@code IbmMqCdcSource}（及未来其它 CDC source）产出。
 *
 * @param meta     {@link CdcMeta}（含 db/table/source/attributes）
 * @param before   变更前快照（INSERT 时为 null）
 * @param after    变更后快照（DELETE 时为 null）
 * @param op       {@link CdcOp}：INSERT/UPDATE/DELETE
 * @param sourceTs source 端时间戳（CDC message 自带）
 */
public record CdcEvent(CdcMeta meta, Map<String, Object> before, Map<String, Object> after, CdcOp op, Instant sourceTs)
        implements Event {}
