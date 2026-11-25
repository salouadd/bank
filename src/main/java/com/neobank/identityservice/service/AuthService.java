package com.neobank.identityservice.service;

import com.neobank.identityservice.dto.AuthResponse;
import com.neobank.identityservice.dto.LoginRequest;
import com.neobank.identityservice.exception.InvalidCredentialsException;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.model.UserStatus;
import com.neobank.identityservice.repository.UserRepository;
import com.neobank.identityservice.security.JwtService;
import com.neobank.identityservice.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional(readOnly = true)
    public AuthResponse authenticate(LoginRequest request) {
        User user = userRepository.findByEmail(EmailNormalizer.normalize(request.getEmail()))
                .orElseThrow(InvalidCredentialsException::new);

        if (user.getStatus() == UserStatus.SUSPENDED) {
            throw new InvalidCredentialsException();
        }

        if (request.getPassword().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidCredentialsException();
        }

        // Vérification sécurisée du mot de passe en clair contre le hash BCrypt
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String jwtToken = jwtService.generateToken(user);

        return AuthResponse.builder()
                .accessToken(jwtToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.getExpirationTime())
                .build();
    }
}