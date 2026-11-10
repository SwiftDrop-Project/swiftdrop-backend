package com.swiftdrop.infrastructure.internal.web;

import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.reference.VehicleType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/meta")
@Tag(name = "Meta", description = "Reference data for forms")
public class MetaController {

    private static final List<DistrictResponse> DISTRICTS = Arrays.stream(District.values())
            .map(d -> new DistrictResponse(d.name(), d.displayName()))
            .sorted(Comparator.comparing(DistrictResponse::name))
            .toList();

    private static final List<VehicleTypeResponse> VEHICLE_TYPES = Arrays.stream(VehicleType.values())
            .map(v -> new VehicleTypeResponse(v.name(), v.displayName()))
            .toList();

    @GetMapping("/districts")
    @PreAuthorize("permitAll()")
    @Operation(summary = "All 25 districts of Sri Lanka, sorted by name (public)")
    public List<DistrictResponse> districts() {
        return DISTRICTS;
    }

    @GetMapping("/vehicle-types")
    @PreAuthorize("permitAll()")
    @Operation(summary = "Supported courier vehicle types (public)")
    public List<VehicleTypeResponse> vehicleTypes() {
        return VEHICLE_TYPES;
    }

    public record DistrictResponse(String code, String name) {
    }

    public record VehicleTypeResponse(String code, String name) {
    }
}
