package com.neobank.identityservice.controller;

import com.neobank.identityservice.dto.KycDocumentResponse;
import com.neobank.identityservice.dto.KycDocumentFile;
import com.neobank.identityservice.dto.KycRejectRequest;
import com.neobank.identityservice.model.DocumentType;
import com.neobank.identityservice.model.KycStatus;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.service.KycService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
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
@Validated
@Tag(name = "KYC", description = "Dépôt et consultation des documents KYC")
@SecurityRequirement(name = "bearerAuth")
public class KycController {

    private final KycService kycService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Déposer un document KYC",
            description = "Utilisateur authentifié. Limite actuelle: 10 MiB; formats PDF, JPEG et PNG vérifiés par signature de contenu.")
    public ResponseEntity<KycDocumentResponse> uploadDocument(
            @AuthenticationPrincipal User user,
            @RequestParam("documentType") DocumentType documentType,
            @RequestParam("documentNumber") @NotBlank(message = "Le numéro du document est obligatoire")
            @Size(max = 100, message = "Le numéro du document ne peut pas dépasser 100 caractères") String documentNumber,
            @RequestParam("file") MultipartFile file
    ) {
        KycDocumentResponse response = kycService.submitDocument(user, documentType, documentNumber, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/documents")
    @Operation(summary = "Lister ses documents KYC", description = "Retourne uniquement les documents de l'utilisateur authentifié.")
    public ResponseEntity<List<KycDocumentResponse>> getMyDocuments(
            @AuthenticationPrincipal User user
    ) {
        return ResponseEntity.ok(kycService.getUserDocuments(user));
    }

    @GetMapping("/admin/pending")
    @Operation(summary = "Lister les dossiers en attente", description = "Réservé au rôle ADMIN.")
    public ResponseEntity<List<KycDocumentResponse>> getPendingDocuments() {
        return ResponseEntity.ok(kycService.getDocumentsByStatus(KycStatus.PENDING));
    }

    @PutMapping("/admin/documents/{id}/verify")
    @Operation(summary = "Vérifier un document", description = "Réservé au rôle ADMIN; une décision ne peut être appliquée qu'à un document PENDING.")
    public ResponseEntity<KycDocumentResponse> verifyDocument(@PathVariable UUID id) {
        return ResponseEntity.ok(kycService.verifyDocument(id));
    }

    @PutMapping("/admin/documents/{id}/reject")
    @Operation(summary = "Rejeter un document", description = "Réservé au rôle ADMIN; le motif est obligatoire et limité à 1000 caractères.")
    public ResponseEntity<KycDocumentResponse> rejectDocument(
            @PathVariable UUID id,
            @Valid @RequestBody KycRejectRequest request
    ) {
        return ResponseEntity.ok(kycService.rejectDocument(id, request.getRejectionReason()));
    }

    @GetMapping("/admin/documents/{id}/file")
    @Operation(summary = "Télécharger un document", description = "Réservé au rôle ADMIN. Le document est renvoyé en pièce jointe avec l'en-tête nosniff.")
    public ResponseEntity<org.springframework.core.io.Resource> downloadDocument(@PathVariable UUID id) {
        KycDocumentFile documentFile = kycService.getDocumentFile(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(documentFile.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("kyc-document").build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(documentFile.resource());
    }
}