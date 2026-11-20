
package com.swiftdrop.settings.internal.application;

import com.swiftdrop.settings.internal.domain.DeliverySettings;
import com.swiftdrop.settings.internal.dto.DeliverySettingsRequest;
import com.swiftdrop.settings.internal.persistence.DeliverySettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class DeliverySettingsService {

    private static final Long SETTINGS_ID = 1L;

    private static final Set<String> VALID_DAYS = Set.of(
        "Monday", "Tuesday", "Wednesday", "Thursday",
        "Friday", "Saturday", "Sunday"
    );

    private final DeliverySettingsRepository repository;

    public DeliverySettingsService(
            DeliverySettingsRepository repository) {
        this.repository = repository;
    }

    public Optional<DeliverySettingsRequest> getDeliverySettings() {
        return repository.findById(SETTINGS_ID)
                .map(this::toResponse);
    }

    public DeliverySettingsRequest updateDeliverySettings(
            DeliverySettingsRequest request) {

        validate(request);

        DeliverySettings settings = new DeliverySettings(
            SETTINGS_ID,
            request.pickupStart(),
            request.pickupEnd(),
            request.sameDayCutoff(),
            String.join(",", request.operatingDays()),
            request.maxWeightKg(),
            request.maxLengthCm(),
            request.maxWidthCm(),
            request.maxHeightCm(),
            request.failedAttempts()
        );

        DeliverySettings saved = repository.save(settings);

        return toResponse(saved);
    }

    private void validate(DeliverySettingsRequest request) {

        if (request.pickupStart() == null ||
            request.pickupEnd() == null ||
            request.sameDayCutoff() == null) {
            badRequest("Pickup times are required.");
        }

        if (!request.pickupStart().isBefore(request.pickupEnd())) {
            badRequest("Pickup end time must be after start time.");
        }

        if (request.sameDayCutoff().isBefore(request.pickupStart()) ||
            request.sameDayCutoff().isAfter(request.pickupEnd())) {
            badRequest("Cut-off time must be within pickup hours.");
        }

        List<String> operatingDays = request.operatingDays();

        if (operatingDays == null || operatingDays.isEmpty()) {
            badRequest("Select at least one operating day.");
        }

        if (!VALID_DAYS.containsAll(operatingDays) ||
            Set.copyOf(operatingDays).size() != operatingDays.size()) {
            badRequest("Invalid or duplicate operating days.");
        }

        if (request.failedAttempts() == null ||
            request.failedAttempts() < 1 ||
            request.failedAttempts() > 5) {
            badRequest("Failed attempts must be between 1 and 5.");
        }
    }

    private void badRequest(String message) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            message
        );
    }

    private DeliverySettingsRequest toResponse(
            DeliverySettings settings) {

        return new DeliverySettingsRequest(
            settings.getPickupStart(),
            settings.getPickupEnd(),
            settings.getSameDayCutoff(),
            Arrays.asList(settings.getOperatingDays().split(",")),
            settings.getMaxWeightKg(),
            settings.getMaxLengthCm(),
            settings.getMaxWidthCm(),
            settings.getMaxHeightCm(),
            settings.getFailedAttempts()
        );
    }
}
