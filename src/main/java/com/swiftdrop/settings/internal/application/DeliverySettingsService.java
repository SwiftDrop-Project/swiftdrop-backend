
package com.swiftdrop.settings.internal.application;

import com.swiftdrop.settings.internal.domain.DeliverySettings;
import com.swiftdrop.settings.internal.dto.DeliverySettingsRequest;
import com.swiftdrop.settings.internal.persistence.DeliverySettingsRepository;
import com.swiftdrop.shared.error.FieldValidationException;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
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

        if (request.pickupStart() == null) {
            badRequest("pickupStart", "Pickup times are required.");
        }
        if (request.pickupEnd() == null) {
            badRequest("pickupEnd", "Pickup times are required.");
        }
        if (request.sameDayCutoff() == null) {
            badRequest("sameDayCutoff", "Pickup times are required.");
        }

        if (!request.pickupStart().isBefore(request.pickupEnd())) {
            badRequest("pickupEnd", "Pickup end time must be after start time.");
        }

        if (request.sameDayCutoff().isBefore(request.pickupStart()) ||
            request.sameDayCutoff().isAfter(request.pickupEnd())) {
            badRequest("sameDayCutoff", "Cut-off time must be within pickup hours.");
        }

        List<String> operatingDays = request.operatingDays();

        if (operatingDays == null || operatingDays.isEmpty()) {
            badRequest("operatingDays", "Select at least one operating day.");
        }

        // Null check first: Set.of(...).containsAll and Set.copyOf throw NullPointerException on a null day.
        if (operatingDays.stream().anyMatch(Objects::isNull) ||
            !VALID_DAYS.containsAll(operatingDays) ||
            Set.copyOf(operatingDays).size() != operatingDays.size()) {
            badRequest("operatingDays", "Invalid or duplicate operating days.");
        }

        if (request.failedAttempts() == null ||
            request.failedAttempts() < 1 ||
            request.failedAttempts() > 5) {
            badRequest("failedAttempts", "Failed attempts must be between 1 and 5.");
        }
    }

    /**
     * 400 VALIDATION_FAILED with {@code fieldErrors: {field: message}}, the same shape as a Bean Validation error,
     * so the admin form can show the message next to the field. (A ResponseStatusException is not mapped by
     * GlobalExceptionHandler and would answer 500.)
     */
    private void badRequest(String field, String message) {
        throw new FieldValidationException(field, message);
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
