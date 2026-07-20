package com.imsw.observe.alerting.application;

public record TimeseriesBucketEpoch(long epochSeconds, String severity, long count) {}
