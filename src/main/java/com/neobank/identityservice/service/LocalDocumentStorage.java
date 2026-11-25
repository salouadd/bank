package com.neobank.identityservice.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "identity.kyc", name = "storage-provider", havingValue = "local", matchIfMissing = true)
public class LocalDocumentStorage implements DocumentStorage {

    private final Path storageRoot;

    public LocalDocumentStorage(DocumentStorageProperties properties) {
        if (properties.getStorageRoot() == null || properties.getMaxFileBytes() < 1) {
            throw new IllegalStateException("La configuration du stockage KYC est invalide");
        }
        this.storageRoot = properties.getStorageRoot().toAbsolutePath().normalize();
    }

    @Override
    public String store(byte[] content) throws IOException {
        Files.createDirectories(storageRoot);
        String storageKey = DocumentStorage.newStorageKey();
        Files.write(resolve(storageKey), content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return storageKey;
    }

    @Override
    public Resource load(String storageKey) throws IOException {
        Path path = resolve(storageKey);
        if (!Files.isRegularFile(path)) {
            throw new IOException("KYC object is unavailable");
        }
        return new UrlResource(path.toUri());
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    private Path resolve(String storageKey) throws IOException {
        if (storageKey == null || !storageKey.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\\.(pdf|jpg|jpeg|png))?")) {
            throw new IOException("Invalid KYC storage key");
        }
        try {
            UUID.fromString(storageKey.substring(0, 36));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid KYC storage key");
        }
        Path path = storageRoot.resolve(storageKey).normalize();
        if (!path.getParent().equals(storageRoot)) {
            throw new IOException("Invalid KYC storage key");
        }
        return path;
    }
}
