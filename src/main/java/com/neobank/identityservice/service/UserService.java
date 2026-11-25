package com.neobank.identityservice.service;

import com.neobank.identityservice.dto.RegisterRequest;
import com.neobank.identityservice.dto.UserResponse;
import com.neobank.identityservice.exception.EmailAlreadyExistsException;
import com.neobank.identityservice.exception.InvalidRequestException;
import com.neobank.identityservice.model.*;
import com.neobank.identityservice.repository.ProfileRepository;
import com.neobank.identityservice.repository.UserRepository;
import com.neobank.identityservice.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String normalizedEmail = EmailNormalizer.normalize(request.getEmail());
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyExistsException(normalizedEmail);
        }
        if (request.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidRequestException("Le mot de passe ne peut pas dépasser 72 octets en UTF-8");
        }

        User user = User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.ROLE_USER)
                .status(UserStatus.PENDING_KYC)
                .build();

        User savedUser;
        try {
            savedUser = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isNormalizedEmailConflict(exception)) {
                throw new EmailAlreadyExistsException(normalizedEmail);
            }
            throw exception;
        }

        Profile profile = Profile.builder()
                .user(savedUser)
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .dateOfBirth(request.getDateOfBirth())
                .address(request.getAddress() == null ? null : request.getAddress().trim())
                .phoneNumber(request.getPhoneNumber() == null ? null : request.getPhoneNumber().trim())
                .build();

        profileRepository.save(profile);

        return UserResponse.builder()
                .id(savedUser.getId())
                .email(savedUser.getEmail())
                .role(savedUser.getRole())
                .status(savedUser.getStatus())
                .firstName(profile.getFirstName())
                .lastName(profile.getLastName())
                .createdAt(savedUser.getCreatedAt())
                .build();
    }

    private boolean isNormalizedEmailConflict(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation
                    && ("ux_users_email_normalized".equals(constraintViolation.getConstraintName())
                    || "users_email_key".equals(constraintViolation.getConstraintName()))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}