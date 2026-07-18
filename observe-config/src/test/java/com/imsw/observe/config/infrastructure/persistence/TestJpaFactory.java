package com.imsw.observe.config.infrastructure.persistence;

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

/**
 * 内存 H2 + JPA 测试基建，镜像 observe-pipeline 的同名工厂，但扫描 config 模块的持久化包。
 *
 * <p>用于需要真实 Repository（落库 + 查询）的 service 集成测试，例如 {@code NamespaceCrudServiceTest}。
 * 与 Mockito 风格的纯单测（{@code PipelineCrudServiceTest}）并存：本模块此前只有 mock 单测，
 * Namespace CRUD 的语义（create 后 findByName 命中、delete 后查不到）必须打到真实 DB 才有意义。
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.imsw.observe.config.infrastructure.persistence")
public class TestJpaFactory {

    @Bean
    public DataSource dataSource() {
        return new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("observe-config-test")
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
        emf.setPackagesToScan("com.imsw.observe.config.infrastructure.persistence");
        return emf;
    }

    @Bean
    public PlatformTransactionManager transactionManager(final LocalContainerEntityManagerFactoryBean emf) {
        return new JpaTransactionManager(emf.getObject());
    }
}
