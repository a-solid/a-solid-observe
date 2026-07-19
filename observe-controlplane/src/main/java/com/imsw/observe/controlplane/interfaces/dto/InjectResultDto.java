package com.imsw.observe.controlplane.interfaces.dto;

/** inject 结果（执行 outcome；告警已真落库，可通过 /alerts 查询）。 */
public record InjectResultDto(String outcome) {}
