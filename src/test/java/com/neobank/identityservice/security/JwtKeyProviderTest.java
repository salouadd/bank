package com.neobank.identityservice.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.identityservice.model.Role;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.model.UserStatus;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Date;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtKeyProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistedSigningKeyCanVerifyTokensAfterProviderIsRecreated() throws Exception {
        KeyPair pair = generateKeyPair();
        JwtKeyProperties firstProperties = propertiesFor("key-v1", pair, "v1");
        JwtKeyProvider firstProvider = new JwtKeyProvider(firstProperties, new DefaultResourceLoader());
        JwtService firstService = new JwtService(firstProvider, firstProperties, new ObjectMapper());
        User user = testUser();
        String token = firstService.generateToken(user);

        JwtKeyProperties restartedProperties = propertiesFor("key-v1", pair, "v1");
        JwtKeyProvider restartedProvider = new JwtKeyProvider(restartedProperties, new DefaultResourceLoader());
        JwtService restartedService = new JwtService(restartedProvider, restartedProperties, new ObjectMapper());

        assertEquals(user.getEmail(), restartedService.extractUsername(token));
        Claims claims = Jwts.parser()
                .verifyWith(restartedProvider.activePublicKey())
                .requireIssuer("identity-service")
                .requireAudience("financial-coach-service")
                .build()
                .parseSignedClaims(token)
                .getPayload();
        assertEquals(user.getId().toString(), claims.get("userId", String.class));
        assertEquals(user.getId(), UUID.fromString(claims.get("userId", String.class)));
        assertEquals(user.getRole().name(), claims.get("role", String.class));
        assertEquals(user.getStatus().name(), claims.get("status", String.class));
        assertEquals(user.getEmail(), claims.getSubject());
        assertEquals("identity-service", claims.getIssuer());
        assertEquals(Set.of("financial-coach-service"), claims.getAudience());
        org.junit.jupiter.api.Assertions.assertNotNull(claims.getIssuedAt());
        org.junit.jupiter.api.Assertions.assertNotNull(claims.getExpiration());
    }

    @Test
    void previousPublicKeyCanVerifyExistingTokensDuringRotation() throws Exception {
        KeyPair oldPair = generateKeyPair();
        KeyPair newPair = generateKeyPair();
        JwtKeyProperties oldProperties = propertiesFor("key-old", oldPair, "old");
        JwtService oldService = new JwtService(
                new JwtKeyProvider(oldProperties, new DefaultResourceLoader()),
                oldProperties,
                new ObjectMapper()
        );
        User user = testUser();
        String tokenSignedByOldKey = oldService.generateToken(user);

        JwtKeyProperties newProperties = propertiesFor("key-new", newPair, "new");
        newProperties.getVerificationKeys().put("key-old", writePublicKey(oldPair, "old-public"));
        JwtKeyProvider rotatingProvider = new JwtKeyProvider(newProperties, new DefaultResourceLoader());
        JwtService rotatingService = new JwtService(rotatingProvider, newProperties, new ObjectMapper());

        assertEquals(user.getEmail(), rotatingService.extractUsername(tokenSignedByOldKey));
        assertEquals(2, rotatingProvider.verificationKeys().size());
    }

    @Test
    void persistentKeysAreRequiredWhenEphemeralKeysAreDisabled() {
        JwtKeyProperties properties = new JwtKeyProperties();
        properties.setAllowEphemeralKeys(false);

        assertThrows(IllegalStateException.class,
                () -> new JwtKeyProvider(properties, new DefaultResourceLoader()));
    }

    @Test
    void productionProfileDisablesEphemeralKeys() throws Exception {
        org.springframework.boot.env.YamlPropertySourceLoader yamlLoader =
                new org.springframework.boot.env.YamlPropertySourceLoader();
        org.springframework.core.env.PropertySource<?> production = yamlLoader
                .load("application-prod", new org.springframework.core.io.ClassPathResource("application-prod.yaml"))
                .getFirst();
        Object allowEphemeralKeys = production.getProperty("identity.jwt.allow-ephemeral-keys");
        assertEquals(Boolean.FALSE, allowEphemeralKeys);
        JwtKeyProperties properties = new JwtKeyProperties();
        properties.setAllowEphemeralKeys(Boolean.TRUE.equals(allowEphemeralKeys));

        assertFalse(properties.isAllowEphemeralKeys());
        assertThrows(IllegalStateException.class,
                () -> new JwtKeyProvider(properties, new DefaultResourceLoader()));
    }

    @Test
    void tamperedTokenAndUnknownKeyIdAreRejected() {
        JwtKeyProperties properties = new JwtKeyProperties();
        JwtKeyProvider provider = new JwtKeyProvider(properties, new DefaultResourceLoader());
        JwtService service = new JwtService(provider, properties, new ObjectMapper());
        String[] parts = service.generateToken(testUser()).split("\\.");
        parts[2] = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);

        assertThrows(JwtException.class, () -> service.extractUsername(String.join(".", parts)));

        String unknownKeyHeader = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"RS256\",\"kid\":\"not-configured\"}".getBytes());
        String unknownKeyToken = unknownKeyHeader + ".e30.AA";
        assertThrows(JwtException.class, () -> service.extractUsername(unknownKeyToken));
    }

    @Test
    void tokenWithDifferentAudienceIsRejected() {
        JwtKeyProperties properties = new JwtKeyProperties();
        JwtKeyProvider provider = new JwtKeyProvider(properties, new DefaultResourceLoader());
        JwtService service = new JwtService(provider, properties, new ObjectMapper());
        Date now = new Date();
        String token = Jwts.builder()
                .subject("person@example.com")
                .issuer(properties.getIssuer())
                .audience().add("another-service").and()
                .issuedAt(now)
                .expiration(new Date(now.getTime() + properties.getExpirationMs()))
                .signWith(provider.activePrivateKey(), Jwts.SIG.RS256)
                .compact();

        assertThrows(JwtException.class, () -> service.extractUsername(token));
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        JwtKeyProperties properties = new JwtKeyProperties();
        properties.setExpirationMs(1_000);
        JwtKeyProvider provider = new JwtKeyProvider(properties, new DefaultResourceLoader());
        JwtService service = new JwtService(provider, properties, new ObjectMapper());
        String token = service.generateToken(testUser());
        Thread.sleep(1_100);

        assertThrows(ExpiredJwtException.class, () -> service.extractUsername(token));
    }

    private JwtKeyProperties propertiesFor(String keyId, KeyPair pair, String filePrefix) throws Exception {
        JwtKeyProperties properties = new JwtKeyProperties();
        properties.setActiveKeyId(keyId);
        properties.setAllowEphemeralKeys(false);
        properties.setPrivateKeyLocation(writePem("-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----",
                pair.getPrivate().getEncoded(), filePrefix + "-private"));
        properties.setPublicKeyLocation(writePublicKey(pair, filePrefix + "-public"));
        return properties;
    }

    private String writePublicKey(KeyPair pair, String fileName) throws Exception {
        return writePem("-----BEGIN PUBLIC KEY-----", "-----END PUBLIC KEY-----",
                pair.getPublic().getEncoded(), fileName);
    }

    private String writePem(String begin, String end, byte[] encoded, String fileName) throws Exception {
        String base64 = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        Path path = temporaryDirectory.resolve(fileName + ".pem");
        Files.writeString(path, begin + "\n" + base64 + "\n" + end + "\n");
        return path.toUri().toString();
    }

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static User testUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("person@example.com")
                .role(Role.ROLE_USER)
                .status(UserStatus.PENDING_KYC)
                .build();
    }
}
