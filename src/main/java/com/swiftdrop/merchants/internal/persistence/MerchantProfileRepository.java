package com.swiftdrop.merchants.internal.persistence;

import com.swiftdrop.merchants.ApprovalStatus;
import com.swiftdrop.merchants.internal.domain.MerchantProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MerchantProfileRepository extends JpaRepository<MerchantProfile, UUID> {

    List<MerchantProfile> findAllByApprovalStatus(ApprovalStatus approvalStatus);
}
