package com.imsw.observe.pipeline.application;

public record ExecutionTimeseriesBucket(
        int year, int month, int day, int hour, String status, long count) {}
