package com.imsw.observe.pipeline.application;

import com.imsw.observe.kernel.event.model.Event;

/**
 * 单事件回调端口（B9 §3.1）。Source 侧每产生一个事件即回调一次；批量语义被去掉，CDC
 * 不再攒批，反压统一由 {@link SourceDispatcher} 的有界队列表达。
 */
public interface EventListener {

    void onEvent(Event event);
}
