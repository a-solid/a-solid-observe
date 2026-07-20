package com.imsw.observe.pipeline.application;

import java.time.Instant;

public record ExecutionTimeseriesPoint(Instant bucketStart, long count, String status) {}
