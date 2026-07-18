package com.imsw.observe.controlplane.interfaces.dto;

import java.time.Instant;

import com.imsw.observe.alerting.application.TimeseriesPoint;

/** 时间序列点响应（B6）。 */
public record TimeseriesPointDto(Instant bucketStart, long count) {

    public static TimeseriesPointDto from(final TimeseriesPoint p) {
        return new TimeseriesPointDto(p.bucketStart(), p.count());
    }
}
