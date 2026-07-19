package com.imsw.observe.kernel.alert.spi;

import com.imsw.observe.kernel.event.model.ExecutionContext;

public interface AlertSink {

    /**
     * 取出 ctx 累积的告警信号并落库（含波次收敛、silence 拦截、evidence）。
     *
     * @return 本次执行是否 emit 了告警（drain 出的信号非空、且至少有一条未被 silence 拦截）。
     *         runner 据此决定 execution 记录的采样与 trigger_event 落库。
     */
    boolean drainAndPersist(ExecutionContext ctx);
}
