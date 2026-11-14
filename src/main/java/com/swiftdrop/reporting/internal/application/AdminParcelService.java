package com.swiftdrop.reporting.internal.application;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelSearch;
import com.swiftdrop.delivery.ParcelSort;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.merchants.MerchantApi;
import com.swiftdrop.merchants.MerchantSummary;
import com.swiftdrop.reporting.internal.dto.AdminParcelDetailResponse;
import com.swiftdrop.reporting.internal.dto.AdminParcelDetailResponse.Sender;
import com.swiftdrop.reporting.internal.dto.AdminParcelDetailResponse.TrackingEntry;
import com.swiftdrop.reporting.internal.dto.AdminParcelResponse;
import com.swiftdrop.reporting.internal.dto.AdminParcelStatsResponse;
import com.swiftdrop.reporting.internal.dto.ParcelPaymentFilter;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The admin's All Parcels page: summary figures, the filtered parcel list, one parcel in full with its history, and a
 * CSV export. Reporting owns no tables: parcels come from delivery, accounts from identity and businesses from
 * merchants, each through its Api.
 */
@Service
public class AdminParcelService {

    /** Booking-date filters are Sri Lanka days. */
    static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");
    static final int MAX_PAGE_SIZE = 100;
    /** The most rows one export writes; the admin narrows the filters for more. */
    static final int MAX_EXPORT_ROWS = 5_000;

    static final Set<ParcelStatus> IN_TRANSIT = EnumSet.of(ParcelStatus.PICKED_UP, ParcelStatus.AT_BRANCH,
            ParcelStatus.IN_TRANSIT, ParcelStatus.OUT_FOR_DELIVERY);

    private final DeliveryApi deliveries;
    private final IdentityApi identity;
    private final MerchantApi merchants;

    public AdminParcelService(DeliveryApi deliveries, IdentityApi identity, MerchantApi merchants) {
        this.deliveries = deliveries;
        this.identity = identity;
        this.merchants = merchants;
    }

    /**
     * The filters of GET /admin/parcels. Every field may be null or empty (no filter).
     *
     * @param createdFrom first booking day (Sri Lanka), included
     * @param createdTo   last booking day (Sri Lanka), included
     */
    public record Filters(String q, Set<ParcelStatus> statuses, District pickupDistrict, District deliveryDistrict,
                          LocalDate createdFrom, LocalDate createdTo, ParcelPaymentFilter payment, ParcelSort sort) {

        /** The search text may be a name or phone number: keep it out of logs. */
        @Override
        public String toString() {
            return "Filters[statuses=" + statuses + ", pickupDistrict=" + pickupDistrict + ", deliveryDistrict="
                    + deliveryDistrict + ", createdFrom=" + createdFrom + ", createdTo=" + createdTo + ", payment="
                    + payment + ", sort=" + sort + "]";
        }
    }

    public AdminParcelStatsResponse stats() {
        Map<ParcelStatus, Long> counts = deliveries.countByStatus();
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long inTransit = IN_TRANSIT.stream().mapToLong(status -> counts.getOrDefault(status, 0L)).sum();
        return new AdminParcelStatsResponse(total, counts.getOrDefault(ParcelStatus.CREATED, 0L), inTransit,
                counts.getOrDefault(ParcelStatus.DELIVERED, 0L), counts.getOrDefault(ParcelStatus.FAILED, 0L), counts);
    }

    public Page<AdminParcelResponse> list(Filters filters, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        return deliveries.search(toSearch(filters), pageRequest).map(AdminParcelResponse::from);
    }

    /** 404 NOT_FOUND for an unknown id. */
    public AdminParcelDetailResponse get(UUID parcelId) {
        DeliverySnapshot parcel = deliveries.findById(parcelId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        return AdminParcelDetailResponse.from(parcel, sender(parcel), history(parcelId));
    }

    /** The parcel's status history, newest first. 404 NOT_FOUND for an unknown id. */
    public List<TrackingEntry> tracking(UUID parcelId) {
        if (deliveries.findById(parcelId).isEmpty()) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        return history(parcelId);
    }

    /** The filtered parcels as CSV, at most {@link #MAX_EXPORT_ROWS} rows, in the list's order. */
    public String exportCsv(Filters filters) {
        ParcelSearch search = toSearch(filters);
        StringBuilder csv = new StringBuilder(ParcelCsv.HEADER);
        int written = 0;
        for (int page = 0; written < MAX_EXPORT_ROWS; page++) {
            Page<DeliverySnapshot> found = deliveries.search(search, PageRequest.of(page, MAX_PAGE_SIZE));
            for (DeliverySnapshot parcel : found.getContent()) {
                if (written == MAX_EXPORT_ROWS) {
                    break;
                }
                csv.append(ParcelCsv.row(parcel));
                written++;
            }
            if (!found.hasNext()) {
                break;
            }
        }
        return csv.toString();
    }

    // ---- Helpers ----

    static ParcelSearch toSearch(Filters filters) {
        if (filters.createdFrom() != null && filters.createdTo() != null
                && filters.createdFrom().isAfter(filters.createdTo())) {
            throw new FieldValidationException("createdFrom", "The start date must be on or before the end date.");
        }
        Boolean cashOnDelivery = filters.payment() == null ? null
                : filters.payment() == ParcelPaymentFilter.CASH_ON_DELIVERY;
        return new ParcelSearch(
                filters.q(),
                filters.statuses(),
                filters.pickupDistrict() == null ? Set.of() : Set.of(filters.pickupDistrict()),
                filters.deliveryDistrict() == null ? Set.of() : Set.of(filters.deliveryDistrict()),
                startOf(filters.createdFrom()),
                filters.createdTo() == null ? null : startOf(filters.createdTo().plusDays(1)),
                cashOnDelivery,
                filters.sort());
    }

    private static Instant startOf(LocalDate day) {
        return day == null ? null : day.atStartOfDay(SRI_LANKA).toInstant();
    }

    private List<TrackingEntry> history(UUID parcelId) {
        return deliveries.history(parcelId).stream().map(TrackingEntry::from).toList();
    }

    private Sender sender(DeliverySnapshot parcel) {
        boolean merchant = parcel.merchantId() != null;
        UUID accountId = merchant ? parcel.merchantId() : parcel.customerId();
        UserSummary account = identity.findById(accountId).orElse(null);
        String businessName = merchant
                ? merchants.findByUserId(accountId).map(MerchantSummary::businessName).orElse(null)
                : null;
        return new Sender(merchant ? "MERCHANT" : "CUSTOMER", accountId,
                account == null ? null : account.fullName(),
                account == null ? null : account.email(),
                businessName);
    }
}
