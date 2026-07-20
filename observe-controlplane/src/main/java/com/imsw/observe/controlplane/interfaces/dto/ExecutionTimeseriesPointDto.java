package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;
import com.imsw.observe.pipeline.application.ExecutionTimeseriesPoint;

/** 执行时间序列点响应（B6）。 */
public record ExecutionTimeseriesPointDto(Instant bucketStart, long count, String status) {

    public static ExecutionTimeseriesPointDto from(final ExecutionTimeseriesPoint p) {
        return new ExecutionTimeseriesPointDto(p.bucketStart(), p.count(), p.status());
    }
}
