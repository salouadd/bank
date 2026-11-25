package com.neobank.identityservice.service;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.UUID;

public interface DocumentStorage {

    String store(byte[] content) throws IOException;

    Resource load(String storageKey) throws IOException;

    void delete(String storageKey) throws IOException;

    static String newStorageKey() {
        return UUID.randomUUID().toString();
    }
}
