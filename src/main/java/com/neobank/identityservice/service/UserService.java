package com.neobank.identityservice.service;

import com.neobank.identityservice.dto.RegisterRequest;
import com.neobank.identityservice.dto.UserResponse;
import com.neobank.identityservice.exception.EmailAlreadyExistsException;
import com.neobank.identityservice.model.*;
import com.neobank.identityservice.repository.ProfileRepository;
import com.neobank.identityservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException(request.getEmail());
        }

        User user = User.builder()
                .email(request.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(Role.ROLE_USER)
                .status(UserStatus.ACTIVE)
                .build();

        User savedUser = userRepository.saveAndFlush(user);

        Profile profile = Profile.builder()
                .user(savedUser)
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .dateOfBirth(request.getDateOfBirth())
                .address(request.getAddress())
                .phoneNumber(request.getPhoneNumber())
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
}