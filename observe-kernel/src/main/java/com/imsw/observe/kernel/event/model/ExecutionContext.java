package com.imsw.observe.kernel.event.model;

import java.util.List;

import com.imsw.observe.kernel.alert.model.AlertSignal;

/**
 * 一次 pipeline 执行的上下文（端口，瘦身版）。
 *
 * <p>四方法表达一次执行的完整语义：在什么 {@link #meta()} 元信息下、针对什么 {@link #event()} 触发事件、
 * 产生了哪些 {@link #emitAlert(AlertSignal)} 告警信号（由 {@link #drainAlerts()} 一次性取出）。
 *
 * <p>历史版本曾有 node 间 KV 传递（putOutput/getOutput/nodeOutputs）+ ExecutionData 子对象——
 * node 输出链零调用、ExecutionData 把输入/输出/派生标志缝一起，均已拆/砍（见 simplify-contexts 重构）。
 */
public interface ExecutionContext {

    ExecutionMeta meta();

    /** 触发本次执行的事件（CdcEvent/TickEvent/ApiEvent/DelayedEvent）。 */
    Event event();

    /** 脚本 emit 一条告警信号（累积，待 drain）。 */
    void emitAlert(AlertSignal signal);

    /** 取出并清空累积的告警信号（sink 消费）。返回后内部的 alerts 被清空。 */
    List<AlertSignal> drainAlerts();
}
