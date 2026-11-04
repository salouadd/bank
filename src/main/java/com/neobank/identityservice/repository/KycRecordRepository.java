package com.neobank.identityservice.repository;

import com.neobank.identityservice.model.KycRecord;
import com.neobank.identityservice.model.KycStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface KycRecordRepository extends JpaRepository<KycRecord, UUID> {
    List<KycRecord> findByUserId(UUID userId);
    List<KycRecord> findByVerificationStatus(KycStatus verificationStatus);
}