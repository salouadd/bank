package com.neobank.identityservice.security;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class JwtKeyProvider {

    private final String activeKeyId;
    private final RSAPrivateKey activePrivateKey;
    private final Map<String, RSAPublicKey> verificationKeys;

    public JwtKeyProvider(JwtKeyProperties properties, ResourceLoader resourceLoader) {
        validateProperties(properties);
        this.activeKeyId = properties.getActiveKeyId();

        KeyPair activePair;
        if (hasConfiguredKeyPair(properties)) {
            activePair = new KeyPair(
                    loadPublicKey(resourceLoader, properties.getPublicKeyLocation()),
                    loadPrivateKey(resourceLoader, properties.getPrivateKeyLocation())
            );
            validateKeyPair(activePair);
        } else {
            activePair = generateEphemeralKeyPair();
        }
        this.activePrivateKey = (RSAPrivateKey) activePair.getPrivate();

        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        keys.put(activeKeyId, (RSAPublicKey) activePair.getPublic());
        properties.getVerificationKeys().forEach((keyId, location) -> {
            if (keyId == null || keyId.isBlank() || location == null || location.isBlank()) {
                throw new IllegalStateException("Chaque clé de vérification JWT doit avoir un kid et un emplacement");
            }
            if (keys.containsKey(keyId)) {
                throw new IllegalStateException("Le kid de vérification JWT est configuré plusieurs fois: " + keyId);
            }
            keys.put(keyId, loadPublicKey(resourceLoader, location));
        });
        this.verificationKeys = Map.copyOf(keys);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public RSAPrivateKey activePrivateKey() {
        return activePrivateKey;
    }

    public RSAPublicKey activePublicKey() {
        return verificationKeys.get(activeKeyId);
    }

    public Map<String, RSAPublicKey> verificationKeys() {
        return verificationKeys;
    }

    private static void validateProperties(JwtKeyProperties properties) {
        if (properties.getActiveKeyId() == null || properties.getActiveKeyId().isBlank()) {
            throw new IllegalStateException("identity.jwt.active-key-id doit être renseigné");
        }
        if (properties.getIssuer() == null || properties.getIssuer().isBlank()) {
            throw new IllegalStateException("identity.jwt.issuer doit être renseigné");
        }
        if (properties.getAudience() == null || properties.getAudience().isBlank()) {
            throw new IllegalStateException("identity.jwt.audience doit être renseigné");
        }
        if (properties.getExpirationMs() < 1_000 || properties.getExpirationMs() > 86_400_000) {
            throw new IllegalStateException("identity.jwt.expiration-ms doit être compris entre 1 seconde et 24 heures");
        }
        boolean hasPrivate = isConfigured(properties.getPrivateKeyLocation());
        boolean hasPublic = isConfigured(properties.getPublicKeyLocation());
        if (hasPrivate != hasPublic) {
            throw new IllegalStateException("Les emplacements de clé privée et publique JWT doivent être fournis ensemble");
        }
        if (!hasPrivate && !properties.isAllowEphemeralKeys()) {
            throw new IllegalStateException("Les clés JWT persistantes sont obligatoires lorsque les clés éphémères sont désactivées");
        }
    }

    private static boolean hasConfiguredKeyPair(JwtKeyProperties properties) {
        return isConfigured(properties.getPrivateKeyLocation());
    }

    private static boolean isConfigured(String value) {
        return value != null && !value.isBlank();
    }

    private static KeyPair generateEphemeralKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("Impossible de générer une clé RSA pour le profil de développement", exception);
        }
    }

    private static void validateKeyPair(KeyPair keyPair) {
        try {
            if (((RSAPublicKey) keyPair.getPublic()).getModulus().bitLength() < 2048) {
                throw new IllegalStateException("La clé publique RSA doit contenir au moins 2048 bits");
            }
            byte[] challenge = "identity-service-key-validation".getBytes(StandardCharsets.US_ASCII);
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(keyPair.getPrivate());
            signer.update(challenge);

            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(keyPair.getPublic());
            verifier.update(challenge);
            if (!verifier.verify(signer.sign())) {
                throw new IllegalStateException("Les clés privée et publique JWT ne correspondent pas");
            }
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Impossible de valider la paire de clés JWT configurée", exception);
        }
    }

    private static RSAPrivateKey loadPrivateKey(ResourceLoader resourceLoader, String location) {
        try {
            byte[] encoded = decodePem(resourceLoader.getResource(location));
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
            return (RSAPrivateKey) key;
        } catch (Exception exception) {
            throw new IllegalStateException("Impossible de charger la clé privée RSA depuis " + location, exception);
        }
    }

    private static RSAPublicKey loadPublicKey(ResourceLoader resourceLoader, String location) {
        try {
            byte[] encoded = decodePem(resourceLoader.getResource(location));
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            if (publicKey.getModulus().bitLength() < 2048) {
                throw new IllegalStateException("La clé publique RSA doit contenir au moins 2048 bits");
            }
            return publicKey;
        } catch (Exception exception) {
            throw new IllegalStateException("Impossible de charger la clé publique RSA depuis " + location, exception);
        }
    }

    private static byte[] decodePem(Resource resource) throws IOException {
        String pem;
        try (InputStream inputStream = resource.getInputStream()) {
            pem = new String(inputStream.readAllBytes(), StandardCharsets.US_ASCII);
        }
        String base64 = pem.replaceAll("-----BEGIN [^-]+-----", "")
                .replaceAll("-----END [^-]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
