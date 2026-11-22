package com.swiftdrop.delivery.internal.controller;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.delivery.internal.dto.DeliveryDetailResponse;
import com.swiftdrop.delivery.internal.dto.DeliveryHistoryEntryResponse;
import com.swiftdrop.delivery.internal.dto.DeliveryResponse;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.web.PageResponse;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.swiftdrop.delivery.ParcelStatus.AT_BRANCH;
import static com.swiftdrop.delivery.ParcelStatus.CREATED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * "My deliveries" without Spring: the customer comes from the token, pages are checked, and only the customer's own
 * delivery is shown. Login and roles (401/403) need the security filters: Postman now, Testcontainers tests later.
 */
class DeliveryControllerTest {

    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final Jwt CUSTOMER = Jwt.withTokenValue("token").header("alg", "none")
            .subject(CUSTOMER_ID.toString()).build();
    private static final Instant BOOKED_AT = Instant.parse("2026-11-08T04:30:00Z");
    private static final Instant ARRIVED_AT = Instant.parse("2026-11-08T09:15:00Z");
    private static final DeliveryContact PICKUP =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Galle Road", "Colombo 03", District.COLOMBO);
    private static final DeliveryContact RECIPIENT =
            new DeliveryContact("Kamala Silva", "+94719876543", "45 Peradeniya Road", "Kandy", District.KANDY);

    private final DeliveryApi deliveries = mock(DeliveryApi.class);
    private final DeliveryController controller = new DeliveryController(deliveries);

    // ---- GET /deliveries ----

    @Test
    void myDeliveriesAsksForTheLoggedInCustomersPage() {
        PageRequest page = PageRequest.of(2, 10);
        DeliverySnapshot mine = parcel(CUSTOMER_ID, null, AT_BRANCH, District.GAMPAHA);
        when(deliveries.findByCustomer(CUSTOMER_ID, AT_BRANCH, page))
                .thenReturn(new PageImpl<>(List.of(mine), page, 21));

        PageResponse<DeliveryResponse> response = controller.list(CUSTOMER, 2, 10, AT_BRANCH);

        assertThat(response.content()).extracting(DeliveryResponse::id).containsExactly(mine.id());
        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(21);
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void noStatusMeansEveryStatusAndTheLargestPageIsAllowed() {
        PageRequest page = PageRequest.of(0, DeliveryController.MAX_PAGE_SIZE);
        when(deliveries.findByCustomer(CUSTOMER_ID, null, page)).thenReturn(Page.empty(page));

        assertThat(controller.list(CUSTOMER, 0, DeliveryController.MAX_PAGE_SIZE, null).content()).isEmpty();

        verify(deliveries).findByCustomer(CUSTOMER_ID, null, page);
    }

    @Test
    void aBadPageOrSizeIsRefusedBeforeAnyQuery() {
        assertFieldError(() -> controller.list(CUSTOMER, -1, 20, null), "page");
        assertFieldError(() -> controller.list(CUSTOMER, 0, 0, null), "size");
        assertFieldError(() -> controller.list(CUSTOMER, 0, DeliveryController.MAX_PAGE_SIZE + 1, null), "size");

        verifyNoInteractions(deliveries);
    }

    // ---- GET /deliveries/{id} ----

    @Test
    void detailGivesTheCustomersOwnDeliveryWithItsHistoryNewestFirst() {
        DeliverySnapshot mine = parcel(CUSTOMER_ID, null, AT_BRANCH, District.GAMPAHA);
        when(deliveries.findById(mine.id())).thenReturn(Optional.of(mine));
        when(deliveries.history(mine.id())).thenReturn(List.of(
                new ParcelStatusEntry(AT_BRANCH, null, null, "COURIER", ARRIVED_AT, District.GAMPAHA),
                new ParcelStatusEntry(CREATED, null, null, "CUSTOMER", BOOKED_AT)));

        DeliveryDetailResponse response = controller.detail(CUSTOMER, mine.id());

        assertThat(response.delivery().id()).isEqualTo(mine.id());
        assertThat(response.delivery().currentBranch()).isEqualTo(District.GAMPAHA);
        assertThat(response.history()).extracting(DeliveryHistoryEntryResponse::status)
                .containsExactly(AT_BRANCH, CREATED);
        assertThat(response.history().get(0).branch()).isEqualTo(District.GAMPAHA);
        assertThat(response.history().get(0).occurredAt()).isEqualTo(ARRIVED_AT);
    }

    @Test
    void someoneElsesDeliveryAMerchantOrderAndAnUnknownIdAreAllNotFound() {
        DeliverySnapshot someoneElses = parcel(UUID.randomUUID(), null, CREATED, null);
        DeliverySnapshot merchantOrder = parcel(null, UUID.randomUUID(), CREATED, null);
        UUID unknown = UUID.randomUUID();
        when(deliveries.findById(someoneElses.id())).thenReturn(Optional.of(someoneElses));
        when(deliveries.findById(merchantOrder.id())).thenReturn(Optional.of(merchantOrder));
        when(deliveries.findById(unknown)).thenReturn(Optional.empty());

        for (UUID id : List.of(someoneElses.id(), merchantOrder.id(), unknown)) {
            assertThatThrownBy(() -> controller.detail(CUSTOMER, id))
                    .isInstanceOfSatisfying(NotFoundException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
        }
        // the history of a parcel that isn't yours is never read
        verify(deliveries, never()).history(any());
    }

    private static void assertFieldError(ThrowingCallable call, String field) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FieldValidationException.class,
                e -> assertThat(e.fieldErrors()).containsOnlyKeys(field));
    }

    private static DeliverySnapshot parcel(UUID customerId, UUID merchantId, ParcelStatus status, District branch) {
        return new DeliverySnapshot(UUID.randomUUID(), "SD2611004817", status, customerId, merchantId, PICKUP,
                RECIPIENT, new BigDecimal("2.50"), null, null, null, null, BOOKED_AT, ARRIVED_AT, null, false, null,
                null, new BigDecimal("450.00"), "LKR", 1L, branch);
    }
}
