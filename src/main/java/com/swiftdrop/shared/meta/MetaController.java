package com.swiftdrop.shared.meta;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

    @GetMapping("/districts")
    @Operation(summary = "All 25 districts of Sri Lanka, sorted by name (public)")
    public List<DistrictResponse> districts() {
        return DISTRICTS;
    }

    public record DistrictResponse(String code, String name) {
    }
}
