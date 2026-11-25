package com.neobank.identityservice.security;

import com.neobank.identityservice.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Service
public class JwtService {

    private final JwtKeyProvider keyProvider;
    private final long jwtExpiration;
    private final String issuer;
    private final String audience;
    private final ObjectMapper objectMapper;

    public JwtService(JwtKeyProvider keyProvider, JwtKeyProperties properties, ObjectMapper objectMapper) {
        this.keyProvider = keyProvider;
        this.jwtExpiration = properties.getExpirationMs();
        this.issuer = properties.getIssuer();
        this.audience = properties.getAudience();
        this.objectMapper = objectMapper;
    }

    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getId().toString());
        claims.put("role", user.getRole().name());
        claims.put("status", user.getStatus().name());
        if (audience != null && !audience.isBlank()) {
            claims.put("aud", audience);
        }

        return Jwts.builder()
                .header()
                .keyId(keyProvider.activeKeyId())
                .and()
                .claims(claims)
                .subject(user.getEmail())
                .issuer(issuer)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(keyProvider.activePrivateKey(), Jwts.SIG.RS256)
                .compact();
    }

    public long getExpirationTime() {
        return jwtExpiration / 1000;
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public boolean isTokenValid(String token, String userEmail) {
        final String username = extractUsername(token);
        return (username.equals(userEmail)) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new JwtException("JWT compact invalide");
        }

        JsonNode header;
        try {
            header = objectMapper.readTree(Decoders.BASE64URL.decode(parts[0]));
        } catch (IOException | IllegalArgumentException exception) {
            throw new JwtException("En-tête JWT invalide", exception);
        }
        if (!"RS256".equals(header.path("alg").asText())) {
            throw new JwtException("Algorithme JWT non pris en charge");
        }
        String keyId = header.path("kid").asText();
        RSAPublicKey verificationKey = keyProvider.verificationKeys().get(keyId);
        if (verificationKey == null) {
            throw new JwtException("Clé JWT inconnue");
        }

        return Jwts.parser()
                .verifyWith(verificationKey)
                .requireIssuer(issuer)
                .requireAudience(audience)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}