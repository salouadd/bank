package com.neobank.identityservice.service;

import com.neobank.identityservice.dto.LoginRequest;
import com.neobank.identityservice.exception.InvalidCredentialsException;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.model.UserStatus;
import com.neobank.identityservice.repository.UserRepository;
import com.neobank.identityservice.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    @Test
    void pendingKycUserCanAuthenticateToCompleteKyc() {
        User user = userWithStatus(UserStatus.PENDING_KYC);
        LoginRequest request = loginRequest("  PERSON@Example.COM  ");

        when(userRepository.findByEmail("person@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.getPassword(), user.getPasswordHash())).thenReturn(true);
        when(jwtService.generateToken(user)).thenReturn("signed-token");
        when(jwtService.getExpirationTime()).thenReturn(900L);

        assertNotNull(authService.authenticate(request).getAccessToken());
    }

    @Test
    void suspendedUserCannotAuthenticate() {
        User user = userWithStatus(UserStatus.SUSPENDED);
        LoginRequest request = loginRequest("person@example.com");
        when(userRepository.findByEmail("person@example.com")).thenReturn(Optional.of(user));

        assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(request));

        verify(passwordEncoder, never()).matches(request.getPassword(), user.getPasswordHash());
        verify(jwtService, never()).generateToken(user);
    }

    @Test
    void loginRejectsPasswordsLongerThanBcryptByteLimit() {
        User user = userWithStatus(UserStatus.ACTIVE);
        LoginRequest request = loginRequest("person@example.com");
        request.setPassword("é".repeat(37));
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.of(user));

        assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(request));

        verify(passwordEncoder, never()).matches(request.getPassword(), user.getPasswordHash());
        verify(jwtService, never()).generateToken(user);
    }

    @Test
    void unknownEmailUsesSameInvalidCredentialsFailureAndDoesNotIssueToken() {
        LoginRequest request = loginRequest("missing@example.com");
        when(userRepository.findByEmail(request.getEmail())).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(request));

        verifyNoInteractions(passwordEncoder, jwtService);
    }

    @Test
    void incorrectPasswordDoesNotIssueToken() {
        User user = userWithStatus(UserStatus.ACTIVE);
        LoginRequest request = loginRequest(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(request.getPassword(), user.getPasswordHash())).thenReturn(false);

        assertThrows(InvalidCredentialsException.class, () -> authService.authenticate(request));

        verify(jwtService, never()).generateToken(user);
    }

    private static LoginRequest loginRequest(String email) {
        return LoginRequest.builder()
                .email(email)
                .password("some-password")
                .build();
    }

    private static User userWithStatus(UserStatus status) {
        return User.builder()
                .id(UUID.randomUUID())
                .email("person@example.com")
                .passwordHash("encoded-password")
                .status(status)
                .build();
    }
}
