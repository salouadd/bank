package com.neobank.identityservice.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalDocumentStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storedDocumentsCanBeReadAndDeleted() throws IOException {
        DocumentStorageProperties properties = new DocumentStorageProperties();
        properties.setStorageRoot(temporaryDirectory);
        DocumentStorage storage = new LocalDocumentStorage(properties);
        byte[] content = "%PDF-1.7 sample".getBytes();

        String key = storage.store(content);
        Resource loaded = storage.load(key);
        try (InputStream inputStream = loaded.getInputStream()) {
            assertArrayEquals(content, inputStream.readAllBytes());
        }

        storage.delete(key);
        assertThrows(IOException.class, () -> storage.load(key));
    }

    @Test
    void storageRejectsPathLikeKeys() {
        DocumentStorageProperties properties = new DocumentStorageProperties();
        properties.setStorageRoot(temporaryDirectory);
        DocumentStorage storage = new LocalDocumentStorage(properties);

        assertThrows(IOException.class, () -> storage.load("..\\secret"));
        assertThrows(IOException.class, () -> storage.delete("../secret"));
    }

    @Test
    void migratedLegacyFilenameKeyRemainsAccessible() throws IOException {
        DocumentStorageProperties properties = new DocumentStorageProperties();
        properties.setStorageRoot(temporaryDirectory);
        DocumentStorage storage = new LocalDocumentStorage(properties);
        String legacyKey = "4e20d468-b215-4fb1-b877-c035f43a84d2.pdf";
        byte[] content = "%PDF-1.7 legacy".getBytes();
        java.nio.file.Files.write(temporaryDirectory.resolve(legacyKey), content);

        try (InputStream inputStream = storage.load(legacyKey).getInputStream()) {
            assertArrayEquals(content, inputStream.readAllBytes());
        }
    }
}
