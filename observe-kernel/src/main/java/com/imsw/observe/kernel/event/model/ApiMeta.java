package com.imsw.observe.kernel.event.model;

import java.util.Map;

/**
 * {@link ApiEvent} 的元数据。sourceType 由子类型隐式 = API，故不再单独字段（ADR-0006）。
 *
 * @param source     API 来源标识（api name）
 * @param apiName    API 名称（与 source 通常一致；显式字段便于脚本/索引）
 * @param attributes 附加属性（HTTP headers 等可挂这里）
 */
public record ApiMeta(String source, String apiName, Map<String, Object> attributes) {}
