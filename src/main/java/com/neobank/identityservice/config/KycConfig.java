package com.neobank.identityservice.config;

import com.neobank.identityservice.service.DocumentStorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DocumentStorageProperties.class)
public class KycConfig {
}
