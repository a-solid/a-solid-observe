package com.imsw.observe.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.imsw.observe.kernel.alert.spi.AlertSink;
import com.imsw.observe.kernel.execution.spi.ExecutionRecorder;
import com.imsw.observe.kernel.transaction.spi.TransactionOperator;
import com.imsw.observe.pipeline.application.PipelineRegistry;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.application.SubscriptionMatcher;

@SpringBootTest(classes = ObserveApplication.class)
class ObserveApplicationTest {

    @Autowired
    private PipelineRunner runner;

    @Autowired
    private SubscriptionMatcher matcher;

    @Autowired
    private PipelineRegistry registry;

    @Autowired
    private AlertSink alertSink;

    @Autowired
    private ExecutionRecorder executionRecorder;

    @Autowired
    private TransactionOperator transactionOperator;

    @Test
    void contextWiresCoreBeans() {
        assertThat(runner).isNotNull();
        assertThat(matcher).isNotNull();
        assertThat(registry).isNotNull();
        assertThat(alertSink).isNotNull();
        assertThat(executionRecorder).isNotNull();
        assertThat(transactionOperator).isNotNull();
    }
}
