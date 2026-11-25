package com.neobank.identityservice.security;

import com.neobank.identityservice.model.User;
import com.neobank.identityservice.model.UserStatus;
import com.neobank.identityservice.repository.UserRepository;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private JwtAuthFilter jwtAuthFilter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void suspendedUserCannotUsePreviouslyIssuedToken() throws ServletException, IOException {
        User user = userWithStatus(UserStatus.SUSPENDED);
        when(jwtService.extractUsername("valid-token")).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));

        runFilter();

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(jwtService, never()).isTokenValid("valid-token", user.getEmail());
    }

    @Test
    void pendingKycUserCanAuthenticateForIdentityAndKycRoutes() throws ServletException, IOException {
        User user = userWithStatus(UserStatus.PENDING_KYC);
        when(jwtService.extractUsername("valid-token")).thenReturn(user.getEmail());
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(jwtService.isTokenValid("valid-token", user.getEmail())).thenReturn(true);

        runFilter();

        assertEquals(user, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
    }

    private void runFilter() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/users/me");
        request.setServletPath("/users/me");
        request.addHeader("Authorization", "Bearer valid-token");
        jwtAuthFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private static User userWithStatus(UserStatus status) {
        return User.builder()
                .id(UUID.randomUUID())
                .email("person@example.com")
                .role(com.neobank.identityservice.model.Role.ROLE_USER)
                .status(status)
                .build();
    }
}
