package com.imsw.observe.kernel.event.model;

import java.util.Map;

/**
 * {@link CdcEvent} 的元数据。sourceType 由子类型隐式 = CDC，故不再单独字段（ADR-0006）。
 *
 * @param source     CDC 来源标识（如 ibm-mq 队列名 / source 通道名）
 * @param db         数据库/schema 名
 * @param table      表名
 * @param attributes 附加属性（透传 CDC 头里非结构化字段）
 */
public record CdcMeta(String source, String db, String table, Map<String, Object> attributes) {}
