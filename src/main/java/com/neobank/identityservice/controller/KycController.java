package com.neobank.identityservice.controller;

import com.neobank.identityservice.dto.KycDocumentResponse;
import com.neobank.identityservice.dto.KycRejectRequest;
import com.neobank.identityservice.model.DocumentType;
import com.neobank.identityservice.model.KycStatus;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.service.KycService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/kyc")
@RequiredArgsConstructor
public class KycController {

    private final KycService kycService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<KycDocumentResponse> uploadDocument(
            @AuthenticationPrincipal User user,
            @RequestParam("documentType") DocumentType documentType,
            @RequestParam("documentNumber") String documentNumber,
            @RequestParam("file") MultipartFile file
    ) {
        KycDocumentResponse response = kycService.submitDocument(user, documentType, documentNumber, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/documents")
    public ResponseEntity<List<KycDocumentResponse>> getMyDocuments(
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(kycService.getUserDocuments(user));
    }

    @GetMapping("/admin/pending")
    public ResponseEntity<List<KycDocumentResponse>> getPendingDocuments() {
        return ResponseEntity.ok(kycService.getDocumentsByStatus(KycStatus.PENDING));
    }

    @PutMapping("/admin/documents/{id}/verify")
    public ResponseEntity<KycDocumentResponse> verifyDocument(@PathVariable UUID id) {
        return ResponseEntity.ok(kycService.verifyDocument(id));
    }

    @PutMapping("/admin/documents/{id}/reject")
    public ResponseEntity<KycDocumentResponse> rejectDocument(
            @PathVariable UUID id,
            @Valid @RequestBody KycRejectRequest request
    ) {
        return ResponseEntity.ok(kycService.rejectDocument(id, request.getRejectionReason()));
    }
}