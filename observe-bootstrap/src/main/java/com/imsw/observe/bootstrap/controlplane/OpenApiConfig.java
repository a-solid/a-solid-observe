package com.imsw.observe.bootstrap.controlplane;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI observeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("a-solid-observe API")
                        .description("Department-wide observation platform: "
                                + "CDC/cron/API triggers, Groovy rules, "
                                + "alerting via Grafana/AlertManager.")
                        .version("0.1.0")
                        .contact(new Contact().name("IMS").email("ims@example.com"))
                        .license(new License().name("Proprietary").url("https://example.com/license")));
    }
}
