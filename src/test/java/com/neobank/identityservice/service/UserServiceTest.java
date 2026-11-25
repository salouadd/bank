package com.neobank.identityservice.service;

import com.neobank.identityservice.dto.RegisterRequest;
import com.neobank.identityservice.dto.UserResponse;
import com.neobank.identityservice.exception.InvalidRequestException;
import com.neobank.identityservice.model.Role;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.model.UserStatus;
import com.neobank.identityservice.repository.ProfileRepository;
import com.neobank.identityservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.dao.DataIntegrityViolationException;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private UserService userService;

    @Test
    void registerCreatesUserWithPendingKycAndUserRole() {
        RegisterRequest request = RegisterRequest.builder()
                .email("  PERSON@Example.COM  ")
                .password("some-password")
                .firstName("Ada")
                .lastName("Lovelace")
                .build();

        when(userRepository.existsByEmail("person@example.com")).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded-password");
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(UUID.randomUUID());
            return user;
        });

        UserResponse response = userService.register(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        assertEquals("person@example.com", userCaptor.getValue().getEmail());
        assertEquals(Role.ROLE_USER, userCaptor.getValue().getRole());
        assertEquals(UserStatus.PENDING_KYC, userCaptor.getValue().getStatus());
        assertEquals(UserStatus.PENDING_KYC, response.getStatus());
        verify(profileRepository).save(any());
    }

    @Test
    void registerRejectsPasswordsLongerThanBcryptByteLimit() {
        RegisterRequest request = RegisterRequest.builder()
                .email("person@example.com")
                .password("é".repeat(37))
                .firstName("Ada")
                .lastName("Lovelace")
                .build();
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);

        assertThrows(InvalidRequestException.class, () -> userService.register(request));

        verify(userRepository, org.mockito.Mockito.never()).saveAndFlush(any(User.class));
    }

    @Test
    void registerRejectsAlreadyRegisteredNormalizedEmailWithoutSaving() {
        RegisterRequest request = RegisterRequest.builder()
                .email("  PERSON@Example.COM  ")
                .password("some-password")
                .firstName("Ada")
                .lastName("Lovelace")
                .build();
        when(userRepository.existsByEmail("person@example.com")).thenReturn(true);

        assertThrows(com.neobank.identityservice.exception.EmailAlreadyExistsException.class,
                () -> userService.register(request));

        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(profileRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void registerMapsConcurrentNormalizedEmailConstraintConflictToConflict() {
        RegisterRequest request = RegisterRequest.builder()
                .email("person@example.com")
                .password("some-password")
                .firstName("Ada")
                .lastName("Lovelace")
                .build();
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encoded-password");
        ConstraintViolationException databaseConflict = new ConstraintViolationException(
                "duplicate normalized email",
                new SQLException("duplicate key"),
                "ux_users_email_normalized"
        );
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key", databaseConflict));

        assertThrows(com.neobank.identityservice.exception.EmailAlreadyExistsException.class,
                () -> userService.register(request));

        verify(profileRepository, never()).save(any());
    }
}
