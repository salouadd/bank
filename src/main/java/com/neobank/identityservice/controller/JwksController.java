package com.neobank.identityservice.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
public class JwksController {

    private final RSAPublicKey publicKey;

    // Injection de votre clé publique RSA configurée dans identity-service
    public JwksController(RSAPublicKey publicKey) {
        this.publicKey = publicKey;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> getJwks() {
        // Encodage base64url du modulus (n) et de l'exposant public (e)
        String n = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getModulus().toByteArray());
        String e = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getPublicExponent().toByteArray());

        Map<String, Object> keyMap = Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", "neobank-auth-key-1",
                "n", n,
                "e", e
        );

        return Map.of("keys", List.of(keyMap));
    }
}