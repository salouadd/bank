package com.neobank.identityservice.controller;

import com.neobank.identityservice.dto.AuthResponse;
import com.neobank.identityservice.dto.LoginRequest;
import com.neobank.identityservice.dto.RegisterRequest;
import com.neobank.identityservice.dto.UserResponse;
import com.neobank.identityservice.model.Profile;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.repository.ProfileRepository;
import com.neobank.identityservice.service.AuthService;
import com.neobank.identityservice.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AuthService authService;
    private final ProfileRepository profileRepository;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserResponse response = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResponse response = authService.authenticate(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(
            @AuthenticationPrincipal User user
    ) {
        // 1. Récupération du profil civil associé à l'utilisateur connecté
        Profile profile = profileRepository.findByUserId(user.getId()).orElse(null);

        // 2. Construction de la réponse avec nom et prénom
        UserResponse response = UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .status(user.getStatus())
                .firstName(profile != null ? profile.getFirstName() : null)
                .lastName(profile != null ? profile.getLastName() : null)
                .createdAt(user.getCreatedAt())
                .build();

        return ResponseEntity.ok(response);
    }
}