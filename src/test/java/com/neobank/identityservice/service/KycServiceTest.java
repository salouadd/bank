package com.neobank.identityservice.service;

import com.neobank.identityservice.exception.InvalidKycTransitionException;
import com.neobank.identityservice.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.identityservice.model.DocumentType;
import com.neobank.identityservice.model.KycRecord;
import com.neobank.identityservice.model.KycStatus;
import com.neobank.identityservice.model.User;
import com.neobank.identityservice.model.UserStatus;
import com.neobank.identityservice.repository.KycRecordRepository;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class KycServiceTest {

    @Mock
    private KycRecordRepository kycRecordRepository;

    @Mock
    private DocumentStorage documentStorage;

    @Mock
    private DocumentStorageProperties storageProperties;

    @InjectMocks
    private KycService kycService;

    @Test
    void verificationOfPendingDocumentActivatesPendingUser() {
        User user = userWithStatus(UserStatus.PENDING_KYC);
        KycRecord record = record(user, KycStatus.PENDING);
        when(kycRecordRepository.findById(record.getId())).thenReturn(Optional.of(record));
        when(kycRecordRepository.save(record)).thenReturn(record);

        kycService.verifyDocument(record.getId());

        assertEquals(KycStatus.VERIFIED, record.getVerificationStatus());
        assertEquals(UserStatus.ACTIVE, user.getStatus());
        verify(kycRecordRepository).save(record);
    }

    @Test
    void rejectionKeepsUserInPendingKyc() {
        User user = userWithStatus(UserStatus.PENDING_KYC);
        KycRecord record = record(user, KycStatus.PENDING);
        when(kycRecordRepository.findById(record.getId())).thenReturn(Optional.of(record));
        when(kycRecordRepository.save(record)).thenReturn(record);

        kycService.rejectDocument(record.getId(), "Document illisible");

        assertEquals(KycStatus.REJECTED, record.getVerificationStatus());
        assertEquals(UserStatus.PENDING_KYC, user.getStatus());
    }

    @Test
    void alreadyDecidedDocumentCannotBeDecidedAgain() {
        KycRecord record = record(userWithStatus(UserStatus.PENDING_KYC), KycStatus.REJECTED);
        when(kycRecordRepository.findById(record.getId())).thenReturn(Optional.of(record));

        assertThrows(InvalidKycTransitionException.class,
                () -> kycService.verifyDocument(record.getId()));
    }

    @Test
    void missingDocumentReturnsNotFound() {
        UUID id = UUID.randomUUID();
        when(kycRecordRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> kycService.verifyDocument(id));
    }

    @Test
    void userDocumentResponseMasksNumberAndDoesNotExposeStoragePath() throws Exception {
        User user = userWithStatus(UserStatus.PENDING_KYC);
        KycRecord record = record(user, KycStatus.PENDING);
        record.setDocumentType(DocumentType.PASSPORT);
        record.setDocumentNumber("123456789");
        record.setStorageKey("private-storage-key");
        when(kycRecordRepository.findByUserId(user.getId())).thenReturn(java.util.List.of(record));

        var response = kycService.getUserDocuments(user).get(0);
        String responseJson = new ObjectMapper().writeValueAsString(response);

        assertEquals("*****6789", response.getDocumentNumberMasked());
        assertFalse(responseJson.contains("123456789"));
        assertFalse(responseJson.contains("private-storage-key"));
    }

    @Test
    void uploadRejectsFileWhoseContentDoesNotMatchItsExtension() {
        when(storageProperties.getMaxFileBytes()).thenReturn(10_000L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "identity.pdf", "application/pdf", "not a pdf".getBytes()
        );

        assertThrows(com.neobank.identityservice.exception.InvalidRequestException.class,
                () -> kycService.submitDocument(
                        userWithStatus(UserStatus.PENDING_KYC), DocumentType.PASSPORT, "123456", file));
        verifyNoInteractions(documentStorage);
    }

    @Test
    void uploadStoresOnlyOpaqueKeyAndValidatedContentType() throws Exception {
        when(storageProperties.getMaxFileBytes()).thenReturn(10_000L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "..\\identity.pdf", "application/pdf", "%PDF-1.7 sample".getBytes()
        );
        when(documentStorage.store(file.getBytes())).thenReturn("4e20d468-b215-4fb1-b877-c035f43a84d2");
        when(kycRecordRepository.saveAndFlush(org.mockito.ArgumentMatchers.any(KycRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        User user = userWithStatus(UserStatus.PENDING_KYC);

        var response = kycService.submitDocument(user, DocumentType.PASSPORT, "123456", file);

        var recordCaptor = org.mockito.ArgumentCaptor.forClass(KycRecord.class);
        verify(kycRecordRepository).saveAndFlush(recordCaptor.capture());
        assertEquals("4e20d468-b215-4fb1-b877-c035f43a84d2", recordCaptor.getValue().getStorageKey());
        assertEquals("application/pdf", recordCaptor.getValue().getContentType());
        assertEquals("**3456", response.getDocumentNumberMasked());
    }

    @Test
    void uploadRejectsFileLargerThanConfiguredLimitBeforeReadingIt() {
        when(storageProperties.getMaxFileBytes()).thenReturn(5L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "identity.pdf", "application/pdf", "%PDF-1.7".getBytes()
        );

        assertThrows(com.neobank.identityservice.exception.InvalidRequestException.class,
                () -> kycService.submitDocument(
                        userWithStatus(UserStatus.PENDING_KYC), DocumentType.PASSPORT, "123456", file));
        verifyNoInteractions(documentStorage);
    }

    @Test
    void uploadRejectsEmptyFileAndUnsupportedExtensionBeforeStorage() {
        when(storageProperties.getMaxFileBytes()).thenReturn(10_000L);
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "identity.pdf", "application/pdf", new byte[0]
        );
        MockMultipartFile unsupportedFile = new MockMultipartFile(
                "file", "identity.svg", "image/svg+xml", "<svg/>".getBytes()
        );

        assertThrows(com.neobank.identityservice.exception.InvalidRequestException.class,
                () -> kycService.submitDocument(
                        userWithStatus(UserStatus.PENDING_KYC), DocumentType.PASSPORT, "123456", emptyFile));
        assertThrows(com.neobank.identityservice.exception.InvalidRequestException.class,
                () -> kycService.submitDocument(
                        userWithStatus(UserStatus.PENDING_KYC), DocumentType.PASSPORT, "123456", unsupportedFile));

        verifyNoInteractions(documentStorage);
        verify(kycRecordRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(KycRecord.class));
    }

    @Test
    void uploadReportsStorageFailureAndDoesNotPersistMetadata() throws Exception {
        when(storageProperties.getMaxFileBytes()).thenReturn(10_000L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "identity.pdf", "application/pdf", "%PDF-1.7 sample".getBytes()
        );
        when(documentStorage.store(file.getBytes())).thenThrow(new java.io.IOException("storage unavailable"));

        assertThrows(IllegalStateException.class,
                () -> kycService.submitDocument(
                        userWithStatus(UserStatus.PENDING_KYC), DocumentType.PASSPORT, "123456", file));

        verify(kycRecordRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any(KycRecord.class));
    }

    @Test
    void uploadRemovesStoredObjectWhenDatabaseWriteFails() throws Exception {
        when(storageProperties.getMaxFileBytes()).thenReturn(10_000L);
        MockMultipartFile file = new MockMultipartFile(
                "file", "identity.pdf", "application/pdf", "%PDF-1.7 sample".getBytes()
        );
        String storageKey = "4e20d468-b215-4fb1-b877-c035f43a84d2";
        when(documentStorage.store(file.getBytes())).thenReturn(storageKey);
        doThrow(new IllegalStateException("database failure"))
                .when(kycRecordRepository).saveAndFlush(org.mockito.ArgumentMatchers.any(KycRecord.class));

        assertThrows(IllegalStateException.class,
                () -> kycService.submitDocument(
                        userWithStatus(UserStatus.PENDING_KYC), DocumentType.PASSPORT, "123456", file));

        verify(documentStorage).delete(storageKey);
    }

    private static User userWithStatus(UserStatus status) {
        return User.builder()
                .id(UUID.randomUUID())
                .email("person@example.com")
                .status(status)
                .build();
    }

    private static KycRecord record(User user, KycStatus status) {
        return KycRecord.builder()
                .id(UUID.randomUUID())
                .user(user)
                .verificationStatus(status)
                .build();
    }
}
