package com.neobank.identityservice.dto;

import org.springframework.core.io.Resource;

public record KycDocumentFile(Resource resource, String contentType) {
}
