package com.neobank.identityservice.config;

import com.neobank.identityservice.model.Role;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminBootstrapRunnerTest {

    @Test
    void promotesOnlyExistingAccountInExplicitLocalProfile() {
        UserRepository userRepository = mock(UserRepository.class);
        User user = user("client@example.com");
        when(userRepository.findByEmail("client@example.com")).thenReturn(Optional.of(user));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.withProperty("identity.admin.bootstrap-only", "true")
                .withProperty("identity.admin.bootstrap-email", " CLIENT@example.com ")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/identity_db");

        runner(userRepository, environment).run(new DefaultApplicationArguments());

        assertEquals(Role.ROLE_ADMIN, user.getRole());
        verify(userRepository).saveAndFlush(user);
    }

    @Test
    void refusesToProvisionWhenProdProfileIsActive() {
        UserRepository userRepository = mock(UserRepository.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local", "prod");
        environment.withProperty("identity.admin.bootstrap-only", "true")
                .withProperty("identity.admin.bootstrap-email", "client@example.com")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/identity_db");

        assertThrows(IllegalStateException.class,
                () -> runner(userRepository, environment).run(new DefaultApplicationArguments()));

        verifyNoInteractions(userRepository);
    }

    @Test
    void refusesToProvisionWithoutExplicitLocalProfile() {
        UserRepository userRepository = mock(UserRepository.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.withProperty("identity.admin.bootstrap-only", "true")
                .withProperty("identity.admin.bootstrap-email", "client@example.com")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/identity_db");

        assertThrows(IllegalStateException.class,
                () -> runner(userRepository, environment).run(new DefaultApplicationArguments()));

        verifyNoInteractions(userRepository);
    }

    @Test
    void doesNothingWhenOneShotProvisioningWasNotRequested() {
        UserRepository userRepository = mock(UserRepository.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");

        runner(userRepository, environment).run(new DefaultApplicationArguments());

        verifyNoInteractions(userRepository);
    }

    @Test
    void doesNotCreateAnAccountWhenTargetDoesNotExist() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.withProperty("identity.admin.bootstrap-only", "true")
                .withProperty("identity.admin.bootstrap-email", "missing@example.com")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/identity_db");

        assertThrows(IllegalStateException.class,
                () -> runner(userRepository, environment).run(new DefaultApplicationArguments()));

        verify(userRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(User.class));
    }

    @Test
    void refusesToProvisionAgainstNonLoopbackDatabase() {
        UserRepository userRepository = mock(UserRepository.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        environment.withProperty("identity.admin.bootstrap-only", "true")
                .withProperty("identity.admin.bootstrap-email", "client@example.com")
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/identity_db");

        assertThrows(IllegalStateException.class,
                () -> runner(userRepository, environment).run(new DefaultApplicationArguments()));

        verifyNoInteractions(userRepository);
    }

    private static AdminBootstrapRunner runner(UserRepository repository, Environment environment) {
        return new AdminBootstrapRunner(repository, environment);
    }

    private static User user(String email) {
        return User.builder()
                .id(UUID.randomUUID())
                .email(email)
                .role(Role.ROLE_USER)
                .build();
    }
}
