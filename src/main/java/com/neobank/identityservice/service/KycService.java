package com.neobank.identityservice.service;

import com.neobank.identityservice.dto.KycDocumentResponse;
import com.neobank.identityservice.model.DocumentType;
import com.neobank.identityservice.model.KycRecord;
import com.neobank.identityservice.model.KycStatus;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.repository.KycRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KycService {

    private final KycRecordRepository kycRecordRepository;
    private static final String UPLOAD_DIR = "uploads/kyc/";
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".pdf", ".jpg", ".jpeg", ".png");

    @Transactional
    public KycDocumentResponse submitDocument(User user, DocumentType documentType, String documentNumber, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Le fichier de document est obligatoire");
        }

        String extension = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Format non supporte. Seuls PDF, JPG, JPEG et PNG sont acceptes");
        }

        try {
            Path uploadPath = Paths.get(UPLOAD_DIR);
            if (!Files.exists(uploadPath)) {
                Files.createDirectories(uploadPath);
            }

            String savedFilename = UUID.randomUUID() + extension;
            Path filePath = uploadPath.resolve(savedFilename);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

            // Appel de fileUrl() conforme à KycRecord
            KycRecord record = KycRecord.builder()
                    .user(user)
                    .documentType(documentType)
                    .documentNumber(documentNumber)
                    .fileUrl(filePath.toString().replace("\\", "/"))
                    .verificationStatus(KycStatus.PENDING)
                    .createdAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .build();

            KycRecord savedRecord = kycRecordRepository.save(record);

            return KycDocumentResponse.builder()
                    .id(savedRecord.getId())
                    .userId(user.getId())
                    .documentType(savedRecord.getDocumentType())
                    .documentNumber(savedRecord.getDocumentNumber())
                    .fileUrl(savedRecord.getFileUrl())
                    .verificationStatus(savedRecord.getVerificationStatus())
                    .submittedAt(savedRecord.getCreatedAt())
                    .build();

        } catch (IOException e) {
            throw new RuntimeException("Echec du stockage physique du document", e);
        }
    }
    // 1. Lister les documents selon leur statut (ex: PENDING)
    @Transactional(readOnly = true)
    public List<KycDocumentResponse> getDocumentsByStatus(KycStatus status) {
        return kycRecordRepository.findByVerificationStatus(status).stream()
                .map(this::mapToResponse)
                .toList();
    }

    // 2. Valider le document et activer l'utilisateur
    @Transactional
    public KycDocumentResponse verifyDocument(UUID documentId) {
        KycRecord record = kycRecordRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document KYC non trouvé avec l'id : " + documentId));

        record.setVerificationStatus(KycStatus.VERIFIED);
        record.setVerifiedAt(OffsetDateTime.now());
        record.setRejectionReason(null);

        // Activation de l'utilisateur associé
        User user = record.getUser();
        user.setStatus(com.neobank.identityservice.model.UserStatus.ACTIVE);

        KycRecord savedRecord = kycRecordRepository.save(record);
        return mapToResponse(savedRecord);
    }

    // 3. Rejeter le document avec motif obligatoire
    @Transactional
    public KycDocumentResponse rejectDocument(UUID documentId, String rejectionReason) {
        KycRecord record = kycRecordRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document KYC non trouvé avec l'id : " + documentId));

        record.setVerificationStatus(KycStatus.REJECTED);
        record.setRejectionReason(rejectionReason);
        record.setVerifiedAt(OffsetDateTime.now());

        KycRecord savedRecord = kycRecordRepository.save(record);
        return mapToResponse(savedRecord);
    }

    private KycDocumentResponse mapToResponse(KycRecord doc) {
        return KycDocumentResponse.builder()
                .id(doc.getId())
                .userId(doc.getUser().getId())
                .documentType(doc.getDocumentType())
                .documentNumber(doc.getDocumentNumber())
                .fileUrl(doc.getFileUrl())
                .verificationStatus(doc.getVerificationStatus())
                .submittedAt(doc.getCreatedAt())
                .build();
    }
    @Transactional(readOnly = true)
    public List<KycDocumentResponse> getUserDocuments(User user) {
        return kycRecordRepository.findByUserId(user.getId()).stream()
                .map(doc -> KycDocumentResponse.builder()
                        .id(doc.getId())
                        .userId(user.getId())
                        .documentType(doc.getDocumentType())
                        .documentNumber(doc.getDocumentNumber())
                        .fileUrl(doc.getFileUrl())
                        .verificationStatus(doc.getVerificationStatus())
                        .submittedAt(doc.getCreatedAt())
                        .build())
                .toList();
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }
        return "";
    }
}