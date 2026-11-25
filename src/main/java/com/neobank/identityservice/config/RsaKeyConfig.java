package com.neobank.identityservice.config;

import com.neobank.identityservice.security.JwtKeyProperties;
import com.neobank.identityservice.security.JwtKeyProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

@Configuration
@EnableConfigurationProperties(JwtKeyProperties.class)
public class RsaKeyConfig {

    @Bean
    public JwtKeyProvider jwtKeyProvider(JwtKeyProperties properties, ResourceLoader resourceLoader) {
        return new JwtKeyProvider(properties, resourceLoader);
    }
}
