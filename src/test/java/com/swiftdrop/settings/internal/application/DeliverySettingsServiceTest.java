package com.swiftdrop.settings.internal.application;

import com.swiftdrop.settings.internal.domain.DeliverySettings;
import com.swiftdrop.settings.internal.dto.DeliverySettingsRequest;
import com.swiftdrop.settings.internal.persistence.DeliverySettingsRepository;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for the admin delivery operations settings and their business rules. Mocks only. */
class DeliverySettingsServiceTest {

    private static final LocalTime START = LocalTime.of(8, 0);
    private static final LocalTime END = LocalTime.of(18, 0);
    private static final LocalTime CUTOFF = LocalTime.of(12, 0);

    private DeliverySettingsRepository repository;
    private DeliverySettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(DeliverySettingsRepository.class);
        when(repository.save(any(DeliverySettings.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new DeliverySettingsService(repository);
    }

    private static DeliverySettingsRequest request(LocalTime start, LocalTime end, LocalTime cutoff,
                                                   List<String> days, Integer failedAttempts) {
        return new DeliverySettingsRequest(start, end, cutoff, days,
                new BigDecimal("25.00"), new BigDecimal("100"), new BigDecimal("60"), new BigDecimal("60"),
                failedAttempts);
    }

    private static DeliverySettingsRequest valid() {
        return request(START, END, CUTOFF, List.of("Monday", "Tuesday", "Friday"), 3);
    }

    /** 400 VALIDATION_FAILED with {@code fieldErrors: {field: message}}, and nothing saved. */
    private void assertBadRequest(DeliverySettingsRequest request, String field, String message) {
        assertThatThrownBy(() -> service.updateDeliverySettings(request))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.fieldErrors()).containsExactly(Map.entry(field, message));
                });
        verify(repository, never()).save(any());
    }

    @Test
    void getReturnsEmptyWhenNothingSavedYet() {
        when(repository.findById(1L)).thenReturn(Optional.empty());

        assertThat(service.getDeliverySettings()).isEmpty();
    }

    @Test
    void getSplitsStoredOperatingDays() {
        when(repository.findById(1L)).thenReturn(Optional.of(new DeliverySettings(1L, START, END, CUTOFF,
                "Monday,Wednesday", null, null, null, null, 2)));

        assertThat(service.getDeliverySettings()).get()
                .extracting(DeliverySettingsRequest::operatingDays)
                .isEqualTo(List.of("Monday", "Wednesday"));
    }

    @Test
    void validUpdateIsSavedAsRowOneWithCommaSeparatedDays() {
        DeliverySettingsRequest result = service.updateDeliverySettings(valid());

        ArgumentCaptor<DeliverySettings> saved = ArgumentCaptor.forClass(DeliverySettings.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(1L);
        assertThat(saved.getValue().getOperatingDays()).isEqualTo("Monday,Tuesday,Friday");
        assertThat(result).isEqualTo(valid());
    }

    @Test
    void cutoffMayEqualPickupStartOrEnd() {
        service.updateDeliverySettings(request(START, END, START, List.of("Monday"), 1));
        service.updateDeliverySettings(request(START, END, END, List.of("Monday"), 5));
    }

    @Test
    void missingTimesAreRejectedOnTheMissingField() {
        assertBadRequest(request(null, END, CUTOFF, List.of("Monday"), 3), "pickupStart", "Pickup times are required.");
        assertBadRequest(request(START, null, CUTOFF, List.of("Monday"), 3), "pickupEnd", "Pickup times are required.");
        assertBadRequest(request(START, END, null, List.of("Monday"), 3), "sameDayCutoff", "Pickup times are required.");
    }

    @Test
    void pickupEndBeforeStartIsRejected() {
        assertBadRequest(request(END, START, CUTOFF, List.of("Monday"), 3),
                "pickupEnd", "Pickup end time must be after start time.");
    }

    @Test
    void equalPickupStartAndEndIsRejected() {
        assertBadRequest(request(START, START, START, List.of("Monday"), 3),
                "pickupEnd", "Pickup end time must be after start time.");
    }

    @Test
    void cutoffOutsidePickupHoursIsRejected() {
        assertBadRequest(request(START, END, LocalTime.of(19, 0), List.of("Monday"), 3),
                "sameDayCutoff", "Cut-off time must be within pickup hours.");
    }

    @Test
    void noOperatingDaysIsRejected() {
        assertBadRequest(request(START, END, CUTOFF, List.of(), 3),
                "operatingDays", "Select at least one operating day.");
    }

    @Test
    void unknownOperatingDayIsRejected() {
        assertBadRequest(request(START, END, CUTOFF, List.of("Funday"), 3),
                "operatingDays", "Invalid or duplicate operating days.");
    }

    @Test
    void duplicateOperatingDayIsRejected() {
        assertBadRequest(request(START, END, CUTOFF, List.of("Monday", "Monday"), 3),
                "operatingDays", "Invalid or duplicate operating days.");
    }

    @Test
    void nullOperatingDayIsRejectedNotACrash() {
        // JSON ["Monday", null] arrives as a list with a null element; Set.of(...).containsAll would throw NPE (500).
        assertBadRequest(request(START, END, CUTOFF, Arrays.asList("Monday", null), 3),
                "operatingDays", "Invalid or duplicate operating days.");
    }

    @Test
    void failedAttemptsOutsideOneToFiveIsRejected() {
        assertBadRequest(request(START, END, CUTOFF, List.of("Monday"), 0),
                "failedAttempts", "Failed attempts must be between 1 and 5.");
        assertBadRequest(request(START, END, CUTOFF, List.of("Monday"), 6),
                "failedAttempts", "Failed attempts must be between 1 and 5.");
        assertBadRequest(request(START, END, CUTOFF, List.of("Monday"), null),
                "failedAttempts", "Failed attempts must be between 1 and 5.");
    }
}
