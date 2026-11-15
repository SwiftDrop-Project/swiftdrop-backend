package com.swiftdrop.reporting.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelSearch;
import com.swiftdrop.delivery.ParcelSort;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.merchants.MerchantApi;
import com.swiftdrop.reporting.internal.application.AdminParcelService.Filters;
import com.swiftdrop.reporting.internal.dto.AdminParcelStatsResponse;
import com.swiftdrop.reporting.internal.dto.ParcelPaymentFilter;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The rules of the All Parcels page, without a database. */
class AdminParcelServiceTest {

    private final DeliveryApi deliveries = mock(DeliveryApi.class);
    private final AdminParcelService service =
            new AdminParcelService(deliveries, mock(IdentityApi.class), mock(MerchantApi.class));

    @Test
    void statsGroupTheStatusesIntoTheSummaryCards() {
        Map<ParcelStatus, Long> counts = new EnumMap<>(ParcelStatus.class);
        counts.put(ParcelStatus.CREATED, 180L);
        counts.put(ParcelStatus.PICKED_UP, 40L);
        counts.put(ParcelStatus.AT_BRANCH, 120L);
        counts.put(ParcelStatus.IN_TRANSIT, 100L);
        counts.put(ParcelStatus.OUT_FOR_DELIVERY, 60L);
        counts.put(ParcelStatus.DELIVERED, 1800L);
        counts.put(ParcelStatus.FAILED, 150L);
        when(deliveries.countByStatus()).thenReturn(counts);

        AdminParcelStatsResponse stats = service.stats();

        assertThat(stats.total()).isEqualTo(2450);
        assertThat(stats.pending()).isEqualTo(180);
        assertThat(stats.inTransit()).isEqualTo(320);
        assertThat(stats.delivered()).isEqualTo(1800);
        assertThat(stats.failed()).isEqualTo(150);
        assertThat(stats.byStatus()).containsEntry(ParcelStatus.AT_BRANCH, 120L);
    }

    @Test
    void bookingDaysAreSriLankaDaysWithTheLastDayIncluded() {
        Filters filters = new Filters(" SD2611 ", Set.of(ParcelStatus.CREATED), District.COLOMBO, District.KANDY,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 13), ParcelPaymentFilter.CASH_ON_DELIVERY,
                ParcelSort.FEE_HIGH);

        ParcelSearch search = AdminParcelService.toSearch(filters);

        // midnight in Colombo (UTC+5:30) is 18:30 UTC the day before
        assertThat(search.createdFrom()).isEqualTo(Instant.parse("2026-10-31T18:30:00Z"));
        assertThat(search.createdBefore()).isEqualTo(Instant.parse("2026-11-13T18:30:00Z"));
        assertThat(search.pickupDistricts()).containsExactly(District.COLOMBO);
        assertThat(search.deliveryDistricts()).containsExactly(District.KANDY);
        assertThat(search.statuses()).containsExactly(ParcelStatus.CREATED);
        assertThat(search.cashOnDelivery()).isTrue();
        assertThat(search.sort()).isEqualTo(ParcelSort.FEE_HIGH);
        assertThat(search.text()).isEqualTo(" SD2611 ");
    }

    @Test
    void noFiltersMeansEveryParcel() {
        ParcelSearch search = AdminParcelService.toSearch(
                new Filters(null, Set.of(), null, null, null, null, null, null));

        assertThat(search.statuses()).isEmpty();
        assertThat(search.pickupDistricts()).isEmpty();
        assertThat(search.createdFrom()).isNull();
        assertThat(search.createdBefore()).isNull();
        assertThat(search.cashOnDelivery()).isNull();
        assertThat(AdminParcelService.toSearch(new Filters(null, null, null, null, null, null,
                ParcelPaymentFilter.PREPAID, null)).cashOnDelivery()).isFalse();
    }

    @Test
    void aStartDateAfterTheEndDateIsRejected() {
        Filters filters = new Filters(null, Set.of(), null, null, LocalDate.of(2026, 11, 10),
                LocalDate.of(2026, 11, 1), null, null);

        assertThatThrownBy(() -> service.list(filters, 0, 20))
                .isInstanceOfSatisfying(FieldValidationException.class,
                        e -> assertThat(e.fieldErrors()).containsKey("createdFrom"));
    }

    @Test
    void anUnknownParcelIsNotFound() {
        UUID id = UUID.randomUUID();
        when(deliveries.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.tracking(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theExportStopsAtTheRowLimit() {
        List<DeliverySnapshot> fullPage = IntStream.range(0, AdminParcelService.MAX_PAGE_SIZE)
                .mapToObj(i -> parcel("SD26110000" + String.format("%02d", i % 100)))
                .toList();
        when(deliveries.search(any(ParcelSearch.class), any(Pageable.class))).thenAnswer(call -> {
            Pageable page = call.getArgument(1);
            return new PageImpl<>(fullPage, page, 1_000_000L);
        });

        String csv = service.exportCsv(new Filters(null, Set.of(), null, null, null, null, null, null));

        long lines = csv.lines().count();
        assertThat(lines).isEqualTo(AdminParcelService.MAX_EXPORT_ROWS + 1L); // plus the header
        assertThat(csv).startsWith("Tracking number,Parcel ID,Status,");
    }

    @Test
    void csvCellsAreQuotedAndFormulasAreDefused() {
        assertThat(ParcelCsv.cell("Kandy")).isEqualTo("Kandy");
        assertThat(ParcelCsv.cell("12, Galle Road")).isEqualTo("\"12, Galle Road\"");
        assertThat(ParcelCsv.cell("Say \"hi\"")).isEqualTo("\"Say \"\"hi\"\"\"");
        assertThat(ParcelCsv.cell("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(ParcelCsv.cell("+94771234567")).isEqualTo("'+94771234567");
        assertThat(ParcelCsv.cell("@cmd")).isEqualTo("'@cmd");
    }

    private static DeliverySnapshot parcel(String trackingNumber) {
        DeliveryContact pickup = new DeliveryContact("Saman Perera", "+94771234567", "12 Galle Road", "Colombo 05",
                District.COLOMBO);
        DeliveryContact recipient = new DeliveryContact("Nimal Silva", "+94711234567", "45 Peradeniya Road", "Kandy",
                District.KANDY);
        Instant now = Instant.parse("2026-11-13T04:00:00Z");
        return new DeliverySnapshot(UUID.randomUUID(), trackingNumber, ParcelStatus.CREATED, UUID.randomUUID(), null,
                pickup, recipient, new BigDecimal("2.50"), null, null, null, null, now, now, null, false, null, null,
                new BigDecimal("450.00"), "LKR", 0L, null);
    }
}
