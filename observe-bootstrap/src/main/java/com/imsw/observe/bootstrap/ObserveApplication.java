package com.imsw.observe.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = "com.imsw.observe")
@EntityScan(
        basePackages = {
            "com.imsw.observe.alerting.infrastructure.persistence",
            "com.imsw.observe.config.infrastructure.persistence",
            "com.imsw.observe.pipeline.infrastructure.persistence"
        })
@EnableJpaRepositories(
        basePackages = {
            "com.imsw.observe.alerting.infrastructure.persistence",
            "com.imsw.observe.config.infrastructure.persistence",
            "com.imsw.observe.pipeline.infrastructure.persistence"
        })
@EnableScheduling
public class ObserveApplication {

    public static void main(final String[] args) {
        SpringApplication.run(ObserveApplication.class, args);
    }
}
