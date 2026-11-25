package com.neobank.identityservice.config;

import com.neobank.identityservice.model.Role;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.repository.UserRepository;
import com.neobank.identityservice.util.EmailNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository userRepository;
    private final Environment environment;
    private final boolean bootstrapOnly;
    private final String bootstrapEmail;

    public AdminBootstrapRunner(
            UserRepository userRepository,
            Environment environment
    ) {
        this.userRepository = userRepository;
        this.environment = environment;
        this.bootstrapOnly = environment.getProperty("identity.admin.bootstrap-only", Boolean.class, false);
        this.bootstrapEmail = environment.getProperty("identity.admin.bootstrap-email", "");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!bootstrapOnly && bootstrapEmail.isBlank()) {
            return;
        }
        if (!bootstrapOnly) {
            throw new IllegalStateException(
                    "Le provisionnement admin exige identity.admin.bootstrap-only=true"
            );
        }

        String[] activeProfiles = environment.getActiveProfiles();
        if (!Arrays.asList(activeProfiles).contains("local")
                || Arrays.asList(activeProfiles).contains("prod")) {
            throw new IllegalStateException(
                    "Le provisionnement admin ponctuel est autorisé uniquement avec le profil local explicite"
            );
        }
        if (bootstrapEmail.isBlank()) {
            throw new IllegalStateException("identity.admin.bootstrap-email doit désigner un compte existant");
        }
        requireLoopbackDatabase();

        String normalizedEmail = EmailNormalizer.normalize(bootstrapEmail);
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalStateException(
                        "Aucun compte existant ne correspond à l'adresse indiquée"
                ));
        if (user.getRole() != Role.ROLE_ADMIN) {
            user.setRole(Role.ROLE_ADMIN);
            userRepository.saveAndFlush(user);
            logger.info("Local admin role provisioned for user id {}", user.getId());
        } else {
            logger.info("User id {} already has the local admin role", user.getId());
        }
    }

    private void requireLoopbackDatabase() {
        String jdbcUrl = environment.getProperty("spring.datasource.url", "");
        String host;
        try {
            host = URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "Le provisionnement admin est limité à une base PostgreSQL locale (localhost)",
                    exception
            );
        }
        if (host == null || !Arrays.asList("localhost", "127.0.0.1", "::1")
                .contains(host.toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException(
                    "Le provisionnement admin est limité à une base PostgreSQL locale (localhost)"
            );
        }
    }
}
