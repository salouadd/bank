package com.neobank.identityservice.service;

import com.neobank.identityservice.dto.KycDocumentResponse;
import com.neobank.identityservice.dto.KycDocumentFile;
import com.neobank.identityservice.exception.ResourceNotFoundException;
import com.neobank.identityservice.exception.InvalidKycTransitionException;
import com.neobank.identityservice.exception.InvalidRequestException;
import com.neobank.identityservice.model.DocumentType;
import com.neobank.identityservice.model.KycRecord;
import com.neobank.identityservice.model.KycStatus;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.model.UserStatus;
import com.neobank.identityservice.repository.KycRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.core.io.Resource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KycService {

    private static final Logger logger = LoggerFactory.getLogger(KycService.class);
    private final KycRecordRepository kycRecordRepository;
    private final DocumentStorage documentStorage;
    private final DocumentStorageProperties storageProperties;
    private static final List<String> ALLOWED_EXTENSIONS = List.of(".pdf", ".jpg", ".jpeg", ".png");

    @Transactional
    public KycDocumentResponse submitDocument(User user, DocumentType documentType, String documentNumber, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("Le fichier de document est obligatoire");
        }
        if (file.getSize() > storageProperties.getMaxFileBytes()) {
            throw new InvalidRequestException("Le fichier dépasse la taille maximale autorisée");
        }

        String extension = extractExtension(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new InvalidRequestException("Format non supporté. Seuls PDF, JPG, JPEG et PNG sont acceptés");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Impossible de lire le document KYC reçu", exception);
        }
        if (content.length == 0 || content.length > storageProperties.getMaxFileBytes()) {
            throw new InvalidRequestException("La taille du fichier est invalide");
        }

        String contentType = validateContent(extension, content);
        String storageKey;
        try {
            storageKey = documentStorage.store(content);
        } catch (IOException exception) {
            throw new IllegalStateException("Impossible de stocker le document KYC", exception);
        }
        registerRollbackCleanup(storageKey);

        try {
            KycRecord record = KycRecord.builder()
                    .user(user)
                    .documentType(documentType)
                    .documentNumber(documentNumber.trim())
                    .storageKey(storageKey)
                    .contentType(contentType)
                    .verificationStatus(KycStatus.PENDING)
                    .createdAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .build();

            KycRecord savedRecord = kycRecordRepository.saveAndFlush(record);
            return mapToResponse(savedRecord);
        } catch (RuntimeException exception) {
            cleanupStoredDocument(storageKey);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<KycDocumentResponse> getDocumentsByStatus(KycStatus status) {
        return kycRecordRepository.findByVerificationStatus(status).stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public KycDocumentResponse verifyDocument(UUID documentId) {
        KycRecord record = findPendingRecord(documentId);

        record.setVerificationStatus(KycStatus.VERIFIED);
        record.setVerifiedAt(OffsetDateTime.now());
        record.setRejectionReason(null);

        User user = record.getUser();
        if (user.getStatus() == UserStatus.PENDING_KYC) {
            user.setStatus(UserStatus.ACTIVE);
        }

        KycRecord savedRecord = kycRecordRepository.save(record);
        return mapToResponse(savedRecord);
    }

    @Transactional
    public KycDocumentResponse rejectDocument(UUID documentId, String rejectionReason) {
        KycRecord record = findPendingRecord(documentId);

        record.setVerificationStatus(KycStatus.REJECTED);
        record.setRejectionReason(rejectionReason.trim());
        record.setVerifiedAt(OffsetDateTime.now());

        KycRecord savedRecord = kycRecordRepository.save(record);
        return mapToResponse(savedRecord);
    }

    private KycRecord findPendingRecord(UUID documentId) {
        KycRecord record = kycRecordRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document KYC non trouvé avec l'id : " + documentId
                ));
        if (record.getVerificationStatus() != KycStatus.PENDING) {
            throw new InvalidKycTransitionException(
                    "Seul un document KYC en attente peut être vérifié ou rejeté"
            );
        }
        return record;
    }

    private KycDocumentResponse mapToResponse(KycRecord doc) {
        return KycDocumentResponse.builder()
                .id(doc.getId())
                .userId(doc.getUser().getId())
                .documentType(doc.getDocumentType())
                .documentNumberMasked(maskDocumentNumber(doc.getDocumentNumber()))
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
                        .documentNumberMasked(maskDocumentNumber(doc.getDocumentNumber()))
                        .verificationStatus(doc.getVerificationStatus())
                        .submittedAt(doc.getCreatedAt())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public KycDocumentFile getDocumentFile(UUID documentId) {
        KycRecord record = kycRecordRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document KYC non trouvé"));
        try {
            Resource resource = documentStorage.load(record.getStorageKey());
            return new KycDocumentFile(resource, record.getContentType());
        } catch (IOException exception) {
            throw new IllegalStateException("Impossible de lire le document KYC stocké", exception);
        }
    }

    private String maskDocumentNumber(String documentNumber) {
        if (documentNumber == null || documentNumber.length() <= 4) {
            return "****";
        }
        return "*".repeat(documentNumber.length() - 4)
                + documentNumber.substring(documentNumber.length() - 4);
    }

    private String extractExtension(String originalFilename) {
        if (originalFilename != null && originalFilename.contains(".")) {
            return originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private String validateContent(String extension, byte[] content) {
        String actualType;
        if (startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            actualType = "application/pdf";
        } else if (content.length >= 8
                && content[0] == (byte) 0x89
                && content[1] == 'P'
                && content[2] == 'N'
                && content[3] == 'G'
                && content[4] == 0x0D
                && content[5] == 0x0A
                && content[6] == 0x1A
                && content[7] == 0x0A) {
            actualType = "image/png";
        } else if (content.length >= 3
                && content[0] == (byte) 0xFF
                && content[1] == (byte) 0xD8
                && content[2] == (byte) 0xFF) {
            actualType = "image/jpeg";
        } else {
            throw new InvalidRequestException("Le contenu du fichier ne correspond pas à un PDF, JPEG ou PNG pris en charge");
        }

        boolean matchesExtension = switch (extension) {
            case ".pdf" -> actualType.equals("application/pdf");
            case ".jpg", ".jpeg" -> actualType.equals("image/jpeg");
            case ".png" -> actualType.equals("image/png");
            default -> false;
        };
        if (!matchesExtension) {
            throw new InvalidRequestException("L'extension ne correspond pas au contenu du fichier");
        }
        return actualType;
    }

    private boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private void registerRollbackCleanup(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    cleanupStoredDocument(storageKey);
                }
            }
        });
    }

    private void cleanupStoredDocument(String storageKey) {
        try {
            documentStorage.delete(storageKey);
        } catch (IOException exception) {
            logger.error("Could not clean up KYC object after database rollback", exception);
        }
    }
}