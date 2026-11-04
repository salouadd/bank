package com.neobank.identityservice.dto;

import com.neobank.identityservice.model.DocumentType;
import com.neobank.identityservice.model.KycStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KycDocumentResponse {
    private UUID id;
    private UUID userId;
    private DocumentType documentType;
    private String documentNumber;
    private String fileUrl;
    private KycStatus verificationStatus;
    private OffsetDateTime submittedAt;
}