package com.imsw.observe.pipeline.infrastructure.persistence;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableJpaRepositories(basePackages = "com.imsw.observe.pipeline.infrastructure.persistence")
public class TestJpaFactory {

    @Bean
    public DataSource dataSource() {
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("observe-pipeline-test")
                .build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(final DataSource dataSource) {
        HibernateJpaVendorAdapter vendor = new HibernateJpaVendorAdapter();
        vendor.setGenerateDdl(true);
        vendor.setDatabasePlatform("org.hibernate.dialect.H2Dialect");
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setJpaVendorAdapter(vendor);
        emf.setPackagesToScan("com.imsw.observe.pipeline.infrastructure.persistence");
        return emf;
    }

    @Bean
    public PlatformTransactionManager transactionManager(final LocalContainerEntityManagerFactoryBean emf) {
        return new JpaTransactionManager(emf.getObject());
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }
}
