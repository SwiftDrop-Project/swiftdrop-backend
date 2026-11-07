// TEMPORARY - delete when WP3 implements DeliveryApi.
package com.swiftdrop.courier.internal.fake;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.shared.reference.District;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TEMPORARY: puts demo parcels for the demo courier ({@code courier@swiftdrop.lk}, created by identity's seeder when
 * {@code SEED_DEMO_USERS=true}) straight into {@link FakeDeliveryApi}. Runs after the account seeders. Sample data
 * only: no real people, nothing written to the database.
 */
@Component
@Order(3)
@ConditionalOnProperty(name = "swiftdrop.dev.fake-delivery", havingValue = "true")
class FakeDeliverySeeder implements ApplicationRunner {

    static final String DEMO_COURIER_EMAIL = "courier@swiftdrop.lk";

    private static final Logger log = LoggerFactory.getLogger(FakeDeliverySeeder.class);
    private static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    private final FakeDeliveryApi fake;
    private final IdentityApi identity;
    private final ListableBeanFactory beans;

    FakeDeliverySeeder(FakeDeliveryApi fake, IdentityApi identity, ListableBeanFactory beans) {
        this.fake = fake;
        this.identity = identity;
        this.beans = beans;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (beans.getBeanNamesForType(DeliveryApi.class).length > 1) {
            log.warn("SWIFTDROP_FAKE_DELIVERY is on but a real DeliveryApi exists: the fake is used. Turn it off.");
        }
        Optional<UserSummary> courier = identity.findByEmail(DEMO_COURIER_EMAIL)
                .filter(user -> user.role() == Role.COURIER);
        if (courier.isEmpty()) {
            log.warn("SWIFTDROP_FAKE_DELIVERY is on but the demo courier account doesn't exist "
                    + "(set SEED_DEMO_USERS=true): no demo parcels.");
            return;
        }
        if (!fake.isEmpty()) {
            return;
        }
        int count = seed(fake, courier.get().id(), UUID.randomUUID(), Instant.now());
        log.warn("SWIFTDROP_FAKE_DELIVERY is on: {} demo parcels in memory for the demo courier (not saved).", count);
    }

    /**
     * Adds the demo parcels: 2 pickups, 1 picked up, 1 in transit, 1 out for delivery with cash on delivery,
     * 1 delivered today, 1 failed yesterday, and 1 parcel of another courier. Returns how many were added.
     */
    static int seed(FakeDeliveryApi fake, UUID courierId, UUID otherCourierId, Instant now) {
        Instant startOfToday = ZonedDateTime.ofInstant(now, SRI_LANKA).toLocalDate().atStartOfDay(SRI_LANKA)
                .toInstant();
        Instant deliveredAt = later(startOfToday, now.minus(Duration.ofMinutes(10)));
        Instant failedAt = startOfToday.minus(Duration.ofHours(3));

        DeliveryContact lankaCrafts = contact("Lanka Crafts", "+94760000101", "12 High Level Road", "Nugegoda",
                District.COLOMBO);
        DeliveryContact teaCorner = contact("Ceylon Tea Corner", "+94760000102", "88 Old Road", "Maharagama",
                District.COLOMBO);

        List<DeliverySnapshot> parcels = List.of(
                parcel(1, ParcelStatus.CREATED, courierId, teaCorner,
                        contact("Nimal Perera", "+94760000201", "45 Peradeniya Road", "Kandy", District.KANDY),
                        "1.20", null, "Fragile: glass jars.", now.minus(Duration.ofHours(5)),
                        now.minus(Duration.ofHours(3)), null, null, null),
                parcel(2, ParcelStatus.CREATED, courierId, lankaCrafts,
                        contact("Shalini Fernando", "+94760000202", "7 Lighthouse Street", "Galle", District.GALLE),
                        "0.80", "1500.00", null, now.minus(Duration.ofHours(4)), now.minus(Duration.ofHours(2)),
                        null, null, null),
                parcel(3, ParcelStatus.PICKED_UP, courierId, lankaCrafts,
                        contact("Ruwan Silva", "+94760000203", "21 Lewis Place", "Negombo", District.GAMPAHA),
                        "2.50", null, null, now.minus(Duration.ofHours(8)), now.minus(Duration.ofHours(7)),
                        now.minus(Duration.ofHours(1)), null, null),
                parcel(4, ParcelStatus.IN_TRANSIT, courierId, teaCorner,
                        contact("Dilani Jayasuriya", "+94760000204", "3 Dambulla Road", "Kurunegala",
                                District.KURUNEGALA),
                        "3.00", null, "Leave with the shop next door if closed.", now.minus(Duration.ofHours(9)),
                        now.minus(Duration.ofHours(8)), now.minus(Duration.ofMinutes(50)), null, null),
                parcel(5, ParcelStatus.OUT_FOR_DELIVERY, courierId, lankaCrafts,
                        contact("Kasun Wickramasinghe", "+94760000205", "15 Galle Road", "Dehiwala",
                                District.COLOMBO),
                        "1.00", "2450.00", "Call before you arrive.", now.minus(Duration.ofHours(10)),
                        now.minus(Duration.ofHours(9)), now.minus(Duration.ofMinutes(30)), null, null),
                parcel(6, ParcelStatus.DELIVERED, courierId, teaCorner,
                        contact("Anoma Rathnayake", "+94760000206", "9 Temple Road", "Kotte", District.COLOMBO),
                        "0.50", null, null, now.minus(Duration.ofHours(12)), now.minus(Duration.ofHours(11)),
                        deliveredAt, "Anoma Rathnayake", null),
                parcel(7, ParcelStatus.FAILED, courierId, lankaCrafts,
                        contact("Tharindu Bandara", "+94760000207", "52 Station Road", "Matara", District.MATARA),
                        "1.50", "990.00", null, failedAt.minus(Duration.ofHours(8)),
                        failedAt.minus(Duration.ofHours(7)), failedAt, null, FailureReason.RECIPIENT_UNAVAILABLE),
                parcel(8, ParcelStatus.OUT_FOR_DELIVERY, otherCourierId, teaCorner,
                        contact("Chamari Peiris", "+94760000208", "4 Lake Drive", "Kandy", District.KANDY),
                        "2.00", null, null, now.minus(Duration.ofHours(6)), now.minus(Duration.ofHours(5)),
                        now.minus(Duration.ofMinutes(20)), null, null));

        for (DeliverySnapshot parcel : parcels) {
            fake.put(parcel, history(parcel));
        }
        return parcels.size();
    }

    private static DeliverySnapshot parcel(int number, ParcelStatus status, UUID courierId, DeliveryContact pickup,
                                           DeliveryContact recipient, String weightKg, String codAmount,
                                           String instructions, Instant createdAt, Instant assignedAt,
                                           Instant changedAt, String proofRecipientName, FailureReason failureReason) {
        Instant updatedAt = changedAt != null ? changedAt : assignedAt;
        return new DeliverySnapshot(UUID.randomUUID(), String.format("SD26110600%02d", number), status, null,
                UUID.randomUUID(), pickup, recipient, new BigDecimal(weightKg),
                codAmount == null ? null : new BigDecimal(codAmount), instructions, courierId, assignedAt, createdAt,
                updatedAt, proofRecipientName, false, failureReason, "DEMO-" + number, new BigDecimal("450.00"),
                "LKR", pathTo(status).size());
    }

    /** The steps from CREATED to the parcel's status, spread between its creation and its last change, newest first. */
    private static List<ParcelStatusEntry> history(DeliverySnapshot parcel) {
        List<ParcelStatus> path = pathTo(parcel.status());
        List<ParcelStatusEntry> entries = new ArrayList<>();
        Duration step = path.size() > 1
                ? Duration.between(parcel.assignedAt(), parcel.updatedAt()).dividedBy(path.size() - 1)
                : Duration.ZERO;
        for (int i = 0; i < path.size(); i++) {
            ParcelStatus status = path.get(i);
            boolean first = i == 0;
            Instant at = first ? parcel.createdAt() : parcel.assignedAt().plus(step.multipliedBy(i));
            if (i == path.size() - 1 && !first) {
                at = parcel.updatedAt();
            }
            entries.add(new ParcelStatusEntry(status, null,
                    status == ParcelStatus.FAILED ? parcel.failureReason() : null,
                    first ? "MERCHANT" : "COURIER", at));
        }
        Collections.reverse(entries);
        return entries;
    }

    /** CREATED, PICKED_UP, ... up to {@code target}, following {@link ParcelStatus#next()}. */
    private static List<ParcelStatus> pathTo(ParcelStatus target) {
        List<ParcelStatus> path = new ArrayList<>(List.of(ParcelStatus.CREATED));
        ParcelStatus current = ParcelStatus.CREATED;
        while (current != target) {
            current = current.next().contains(target) ? target : current.next().iterator().next();
            path.add(current);
        }
        return path;
    }

    private static DeliveryContact contact(String name, String phone, String address, String city,
                                           District district) {
        return new DeliveryContact(name, phone, address, city, district);
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
