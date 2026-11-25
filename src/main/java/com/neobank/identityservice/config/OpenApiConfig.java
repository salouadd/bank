package com.neobank.identityservice.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI identityServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Identity Service API")
                        .version("1.0")
                        .description("""
                                API d'identité et de vérification KYC.
                                Les routes protégées utilisent un JWT Bearer RS256.
                                Les routes d'administration KYC exigent le rôle ADMIN.
                                """))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
