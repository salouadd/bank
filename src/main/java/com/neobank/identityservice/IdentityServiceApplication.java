package com.neobank.identityservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class IdentityServiceApplication {

    public static void main(String[] args) {
        boolean localAdminBootstrap = Arrays.asList(args).contains("--identity.admin.bootstrap-only=true")
                || Boolean.parseBoolean(System.getProperty("identity.admin.bootstrap-only", "false"));
        if (localAdminBootstrap) {
            SpringApplication application = new SpringApplication(IdentityServiceApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            try (ConfigurableApplicationContext ignored = application.run(args)) {
            }
            return;
        }
        SpringApplication.run(IdentityServiceApplication.class, args);
    }

}
