package com.swiftdrop.merchants.infrastructure;

import com.swiftdrop.merchants.domain.ApprovalStatus;
import com.swiftdrop.merchants.domain.MerchantProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MerchantProfileRepository extends JpaRepository<MerchantProfile, UUID> {

    List<MerchantProfile> findAllByApprovalStatus(ApprovalStatus approvalStatus);
}
