package com.neobank.identityservice.security;

import com.neobank.identityservice.model.KycStatus;
import com.neobank.identityservice.model.Role;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.model.UserStatus;
import com.neobank.identityservice.dto.AuthResponse;
import com.neobank.identityservice.dto.RegisterRequest;
import com.neobank.identityservice.dto.UserResponse;
import com.neobank.identityservice.repository.ProfileRepository;
import com.neobank.identityservice.repository.UserRepository;
import com.neobank.identityservice.service.AuthService;
import com.neobank.identityservice.service.KycService;
import com.neobank.identityservice.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.identityservice.dto.KycDocumentFile;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.io.ByteArrayResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KycService kycService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private ProfileRepository profileRepository;

    @Test
    void registrationRouteIsPublic() throws Exception {
        UUID userId = UUID.randomUUID();
        when(userService.register(any(RegisterRequest.class))).thenReturn(UserResponse.builder()
                .id(userId)
                .email("person@example.com")
                .role(Role.ROLE_USER)
                .status(UserStatus.PENDING_KYC)
                .build());

        mockMvc.perform(post("/users/register")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"person@example.com","password":"some-password","firstName":"First","lastName":"Last"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.id").value(userId.toString()));
    }

    @Test
    void loginRouteIsPublic() throws Exception {
        when(authService.authenticate(any())).thenReturn(AuthResponse.builder()
                .accessToken("test-access-token")
                .tokenType("Bearer")
                .expiresIn(900)
                .build());

        mockMvc.perform(post("/users/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"person@example.com\",\"password\":\"some-password\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.tokenType").value("Bearer"));
    }

    @Test
    void currentUserRouteRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentUserRouteAcceptsValidJwt() throws Exception {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("person@example.com")
                .role(Role.ROLE_USER)
                .status(UserStatus.PENDING_KYC)
                .build();
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(profileRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        mockMvc.perform(get("/users/me")
                        .header("Authorization", "Bearer " + jwtService.generateToken(user)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.id").value(user.getId().toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.email").value(user.getEmail()));
    }

    @Test
    void protectedEndpointRejectsAnonymousRequest() throws Exception {
        mockMvc.perform(get("/kyc/admin/pending"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(kycService);
    }

    @Test
    void protectedEndpointRejectsMalformedBearerToken() throws Exception {
        mockMvc.perform(get("/kyc/admin/pending").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(kycService);
    }

    @Test
    void invalidLoginRequestUsesDocumentedValidationErrorShape() throws Exception {
        mockMvc.perform(post("/users/login")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value(400))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.error").value("Bad Request"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.path").value("/users/login"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.validationErrors.email").exists())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.validationErrors.password").exists());
    }

    @Test
    void basicAuthenticationIsNotAccepted() throws Exception {
        mockMvc.perform(get("/kyc/admin/pending")
                        .with(SecurityMockMvcRequestPostProcessors.httpBasic("user", "password")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(kycService);
    }

    @Test
    void regularUserCannotAccessAdminKycRoute() throws Exception {
        mockMvc.perform(get("/kyc/admin/pending")
                        .with(SecurityMockMvcRequestPostProcessors.user("regular-user").roles("USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(kycService);
    }

    @Test
    void administratorCanAccessAdminKycRoute() throws Exception {
        when(kycService.getDocumentsByStatus(KycStatus.PENDING)).thenReturn(List.of());

        mockMvc.perform(get("/kyc/admin/pending")
                        .with(SecurityMockMvcRequestPostProcessors.user("administrator").roles("ADMIN")))
                .andExpect(status().isOk());

        verify(kycService).getDocumentsByStatus(KycStatus.PENDING);
    }

    @Test
    void onlyAdministratorCanReadKycDocumentFile() throws Exception {
        mockMvc.perform(get("/kyc/admin/documents/{id}/file", java.util.UUID.randomUUID())
                        .with(SecurityMockMvcRequestPostProcessors.user("regular-user").roles("USER")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(kycService);
    }

    @Test
    void administratorDownloadsDocumentAsAttachmentWithNosniffHeader() throws Exception {
        UUID documentId = UUID.randomUUID();
        when(kycService.getDocumentFile(documentId))
                .thenReturn(new KycDocumentFile(new ByteArrayResource("%PDF-1.7".getBytes()), "application/pdf"));

        mockMvc.perform(get("/kyc/admin/documents/{id}/file", documentId)
                        .with(SecurityMockMvcRequestPostProcessors.user("administrator").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void onlyHealthCheckIsPublicAmongActuatorRoutes() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthResponseHasSafeReadinessAndCorrelationId() throws Exception {
        mockMvc.perform(get("/actuator/health").header("X-Correlation-ID", "invalid-user-value"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists("X-Correlation-ID"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Correlation-ID", org.hamcrest.Matchers.not("invalid-user-value")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.components").doesNotExist());
    }

    @Test
    void validCorrelationIdIsReturnedUnchanged() throws Exception {
        String correlationId = "e77aa301-f4d5-44bc-9dcf-9f8720ac9a25";
        mockMvc.perform(get("/actuator/health").header("X-Correlation-ID", correlationId))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().string("X-Correlation-ID", correlationId));
    }

    @Test
    void jwksEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk());
    }

    @Test
    void openApiContractIsPublicAndDeclaresBearerAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.info.title").value("Identity Service API"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.paths['/kyc/admin/pending'].get.security[0].bearerAuth").exists());
    }

    @Test
    void jwtKidAndJwksKeyVerifyTheIssuedToken() throws Exception {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("person@example.com")
                .role(Role.ROLE_USER)
                .status(UserStatus.PENDING_KYC)
                .build();
        String token = jwtService.generateToken(user);
        String[] parts = token.split("\\.");
        JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));

        MvcResult jwksResult = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode jwks = objectMapper.readTree(jwksResult.getResponse().getContentAsString());
        JsonNode key = jwks.path("keys").get(0);

        assertEquals("RS256", header.path("alg").asText());
        assertEquals(key.path("kid").asText(), header.path("kid").asText());
        assertEquals("RSA", key.path("kty").asText());
        assertFalse(key.has("d"));
        assertFalse(key.has("privateKey"));

        byte[] modulusBytes = Base64.getUrlDecoder().decode(key.path("n").asText());
        byte[] exponentBytes = Base64.getUrlDecoder().decode(key.path("e").asText());
        RSAPublicKey jwksPublicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(new BigInteger(1, modulusBytes), new BigInteger(1, exponentBytes))
        );
        Claims claims = Jwts.parser()
                .verifyWith(jwksPublicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals(user.getEmail(), claims.getSubject());
        assertEquals(user.getId().toString(), claims.get("userId", String.class));
        assertEquals(user.getRole().name(), claims.get("role", String.class));
        assertEquals(user.getStatus().name(), claims.get("status", String.class));
        assertEquals("identity-service", claims.getIssuer());
        assertEquals(Set.of("financial-coach-service"), claims.getAudience());
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }
}
