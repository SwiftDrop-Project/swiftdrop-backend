package com.swiftdrop.pricing.internal.persistence;

import com.swiftdrop.pricing.internal.domain.DistrictLocation;
import com.swiftdrop.shared.reference.District;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DistrictLocationRepository extends JpaRepository<DistrictLocation, District> {
}
