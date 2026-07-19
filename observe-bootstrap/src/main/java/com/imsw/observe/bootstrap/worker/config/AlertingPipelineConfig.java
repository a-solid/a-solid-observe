package com.imsw.observe.bootstrap.worker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.imsw.observe.alerting.infrastructure.AlertResolveJob;
import com.imsw.observe.alerting.infrastructure.DefaultAlertSink;
import com.imsw.observe.alerting.infrastructure.alert.DefaultAlertsApi;
import com.imsw.observe.alerting.infrastructure.evidence.AnnotationRenderer;
import com.imsw.observe.alerting.infrastructure.evidence.EvidenceCollector;
import com.imsw.observe.alerting.infrastructure.persistence.alert.AlertRepository;
import com.imsw.observe.alerting.infrastructure.persistence.evidence.EvidenceRepository;
import com.imsw.observe.kernel.alert.spi.AlertSink;
import com.imsw.observe.kernel.script.spi.DbApi;
import com.imsw.observe.kernel.script.spi.GroovyScriptEngine;
import com.imsw.observe.pipeline.application.ExecutionQueryService;
import com.imsw.observe.pipeline.application.PipelineExecutor;
import com.imsw.observe.pipeline.application.PipelineRunner;
import com.imsw.observe.pipeline.application.SubscriptionMatcher;
import com.imsw.observe.pipeline.infrastructure.engine.DefaultPipelineRunner;
import com.imsw.observe.pipeline.infrastructure.engine.LinearPipelineExecutor;
import com.imsw.observe.pipeline.infrastructure.engine.ScriptNode;
import com.imsw.observe.pipeline.infrastructure.script.GroovyScriptEngineImpl;
import com.imsw.observe.pipeline.infrastructure.subscription.DefaultSubscriptionMatcher;

@Configuration
public class AlertingPipelineConfig {

    @Bean
    public GroovyScriptEngine groovyScriptEngine() {
        return new GroovyScriptEngineImpl();
    }

    @Bean
    public com.imsw.observe.config.application.PipelineValidator pipelineValidator(final GroovyScriptEngine engine) {
        return new com.imsw.observe.config.application.PipelineValidator(engine);
    }

    @Bean
    @ConditionalOnMissingBean(AlertSink.class)
    public AlertSink defaultAlertSink(
            final AlertRepository alertRepository,
            final EvidenceRepository evidenceRepository,
            final EvidenceCollector evidenceCollector,
            final AnnotationRenderer annotationRenderer,
            final com.imsw.observe.alerting.infrastructure.AlertSilenceMatcher silenceMatcher,
            final com.imsw.observe.kernel.util.SnowflakeIdGenerator snowflakeIdGenerator) {
        return new DefaultAlertSink(
                alertRepository,
                evidenceRepository,
                evidenceCollector,
                annotationRenderer,
                silenceMatcher,
                snowflakeIdGenerator,
                new com.imsw.observe.alerting.domain.WavePolicy());
    }

    @Bean
    public AlertResolveJob alertResolveJob(final AlertRepository alertRepository) {
        return new AlertResolveJob(alertRepository, 1000);
    }

    @Bean
    public com.imsw.observe.alerting.infrastructure.AlertSilenceMatcher alertSilenceMatcher(
            final com.imsw.observe.alerting.infrastructure.persistence.silence.AlertSilenceRepository silenceRepository,
            final org.springframework.core.env.Environment env) {
        long ttlMillis = env.getProperty("observe.alerting.silence.cache-ttl-millis", Long.class, 10_000L);
        return new com.imsw.observe.alerting.infrastructure.AlertSilenceMatcher(
                silenceRepository, java.time.Duration.ofMillis(ttlMillis));
    }

    @Bean
    public ExecutionQueryService executionQueryService(
            final com.imsw.observe.pipeline.infrastructure.persistence.ExecutionRepository executionRepository,
            final com.imsw.observe.pipeline.infrastructure.persistence.FailedExecutionRepository
                    failedExecutionRepository) {
        return new ExecutionQueryService(executionRepository, failedExecutionRepository);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            com.imsw.observe.kernel.execution.spi.ExecutionRecorder.class)
    public com.imsw.observe.kernel.execution.spi.ExecutionRecorder executionRecorder(
            final com.imsw.observe.pipeline.infrastructure.persistence.ExecutionRepository executionRepository,
            final com.imsw.observe.pipeline.infrastructure.persistence.FailedExecutionRepository
                    failedExecutionRepository,
            final com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            final com.imsw.observe.kernel.util.SnowflakeIdGenerator snowflakeIdGenerator) {
        return new com.imsw.observe.pipeline.infrastructure.persistence.JpaExecutionRecorder(
                executionRepository, failedExecutionRepository, objectMapper, snowflakeIdGenerator);
    }

    @Bean
    public PipelineExecutor pipelineExecutor(final GroovyScriptEngine engine, final DbApi dbApi) {
        return new LinearPipelineExecutor(
                spec -> new ScriptNode(engine, ctx -> new DefaultAlertsApi(ctx), () -> dbApi));
    }

    @Bean
    @ConditionalOnMissingBean(PipelineRunner.class)
    public PipelineRunner pipelineRunner(
            final PipelineExecutor executor,
            final AlertSink alertSink,
            final com.imsw.observe.kernel.transaction.spi.TransactionOperator transactionOperator,
            final com.imsw.observe.kernel.execution.spi.ExecutionRecorder executionRecorder,
            final com.imsw.observe.kernel.util.SnowflakeIdGenerator snowflakeIdGenerator) {
        return new DefaultPipelineRunner(
                executor, alertSink, transactionOperator, executionRecorder, snowflakeIdGenerator);
    }

    @Bean
    @ConditionalOnMissingBean(SubscriptionMatcher.class)
    public SubscriptionMatcher subscriptionMatcher(
            final com.imsw.observe.pipeline.application.PipelineRegistry registry) {
        return new DefaultSubscriptionMatcher(registry);
    }
}
