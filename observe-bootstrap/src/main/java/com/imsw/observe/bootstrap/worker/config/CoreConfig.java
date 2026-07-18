package com.imsw.observe.bootstrap.worker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.imsw.observe.alerting.infrastructure.evidence.AnnotationRenderer;
import com.imsw.observe.alerting.infrastructure.evidence.EvidenceCollector;
import com.imsw.observe.bootstrap.worker.db.JdbcDbApi;
import com.imsw.observe.config.infrastructure.ConditionCodec;
import com.imsw.observe.kernel.script.spi.DbApi;
import com.imsw.observe.kernel.transaction.spi.TransactionOperator;
import com.imsw.observe.kernel.util.JsonUtil;
import com.imsw.observe.kernel.util.SnowflakeIdGenerator;
import com.imsw.observe.pipeline.infrastructure.transaction.SpringTransactionOperator;

@Configuration
public class CoreConfig {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return JsonUtil.mapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(
            final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean(DbApi.class)
    public DbApi dbApi(final NamedParameterJdbcTemplate jdbc) {
        return new JdbcDbApi(jdbc);
    }

    @Bean
    public ConditionCodec conditionCodec() {
        return new ConditionCodec();
    }

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        // 一期单 worker，workerId 硬编码 1；多 worker 时由协调分配（二期）
        return new SnowflakeIdGenerator(1L, 0L);
    }

    @Bean
    public EvidenceCollector evidenceCollector(final ObjectMapper objectMapper) {
        return new EvidenceCollector(objectMapper);
    }

    @Bean
    public AnnotationRenderer annotationRenderer() {
        return new AnnotationRenderer();
    }

    @Bean
    @ConditionalOnMissingBean(TransactionOperator.class)
    public TransactionOperator transactionOperator(final PlatformTransactionManager txManager) {
        return new SpringTransactionOperator(new TransactionTemplate(txManager));
    }
}
