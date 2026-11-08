// TEMPORARY - delete when WP3 implements DeliveryApi and WP8 implements AssignmentApi.
package com.swiftdrop.courier.internal.fake;

import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.dispatch.AssignmentApi;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * TEMPORARY: puts the multi-branch demo (Delgoda → Gampaha branch → Matara branch → Deniyaya) straight into
 * {@link FakeDeliveryApi} and {@link FakeAssignmentApi}, for the demo courier ({@code courier@swiftdrop.lk}, created by
 * identity's seeder when {@code SEED_DEMO_USERS=true}). On only when both fakes are on. Runs after the account
 * seeders. Sample data only: no real people, nothing written to the database.
 */
@Component
@Order(3)
@ConditionalOnProperty(name = {"swiftdrop.dev.fake-delivery", "swiftdrop.dev.fake-dispatch"}, havingValue = "true")
class FakeDeliverySeeder implements ApplicationRunner {

    static final String DEMO_COURIER_EMAIL = "courier@swiftdrop.lk";

    private static final Logger log = LoggerFactory.getLogger(FakeDeliverySeeder.class);
    private static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");

    /** What {@link #seed} added. */
    record Seeded(int parcels, int assignments) {
    }

    private final FakeDeliveryApi deliveries;
    private final FakeAssignmentApi assignments;
    private final IdentityApi identity;
    private final ListableBeanFactory beans;

    FakeDeliverySeeder(FakeDeliveryApi deliveries, FakeAssignmentApi assignments, IdentityApi identity,
                       ListableBeanFactory beans) {
        this.deliveries = deliveries;
        this.assignments = assignments;
        this.identity = identity;
        this.beans = beans;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (beans.getBeanNamesForType(DeliveryApi.class).length > 1) {
            log.warn("SWIFTDROP_FAKE_DELIVERY is on but a real DeliveryApi exists: the fake is used. Turn it off.");
        }
        if (beans.getBeanNamesForType(AssignmentApi.class).length > 1) {
            log.warn("SWIFTDROP_FAKE_DISPATCH is on but a real AssignmentApi exists: the fake is used. Turn it off.");
        }
        Optional<UserSummary> courier = identity.findByEmail(DEMO_COURIER_EMAIL)
                .filter(user -> user.role() == Role.COURIER);
        if (courier.isEmpty()) {
            log.warn("The courier fakes are on but the demo courier account doesn't exist "
                    + "(set SEED_DEMO_USERS=true): no demo tasks.");
            return;
        }
        if (!deliveries.isEmpty() || !assignments.isEmpty()) {
            return;
        }
        Seeded seeded = seed(deliveries, assignments, courier.get().id(), UUID.randomUUID(), Instant.now());
        log.warn("The courier fakes are on: {} demo parcels and {} demo tasks in memory (not saved).",
                seeded.parcels(), seeded.assignments());
    }

    /**
     * Adds the demo for {@code courierId}, all ASSIGNED and planned for today:
     * <ul>
     *   <li>PICKUP to the Gampaha branch: 3 parcels from senders around Delgoda (CREATED)</li>
     *   <li>TRANSFER Gampaha → Matara: 2 parcels waiting at the Gampaha branch</li>
     *   <li>DELIVERY from the Matara branch: 3 parcels for Deniyaya, Morawaka and Kotapola (one with cash on
     *       delivery)</li>
     * </ul>
     * Plus one TRANSFER of another courier (a parcel in transit), which the demo courier must not see.
     */
    static Seeded seed(FakeDeliveryApi deliveries, FakeAssignmentApi assignments, UUID courierId,
                       UUID otherCourierId, Instant now) {
        LocalDate today = now.atZone(SRI_LANKA).toLocalDate();

        DeliveryContact spiceTraders = contact("Delgoda Spice Traders", "+94760000101", "14 Kandy Road", "Delgoda",
                District.GAMPAHA);
        DeliveryContact handlooms = contact("Kirindiwita Handlooms", "+94760000102", "3 Temple Lane", "Kirindiwita",
                District.GAMPAHA);
        DeliveryContact bookShop = contact("Weliweriya Book Shop", "+94760000103", "27 Main Street", "Weliweriya",
                District.GAMPAHA);
        DeliveryContact batikHouse = contact("Ganemulla Batik House", "+94760000104", "9 Station Road", "Ganemulla",
                District.GAMPAHA);

        // Stage 1: waiting to be collected around Delgoda
        Instant createdThisMorning = now.minus(Duration.ofHours(5));
        DeliverySnapshot p1 = parcel(1, spiceTraders,
                contact("Nimal Perera", "+94760000201", "45 Rakwana Road", "Deniyaya", District.MATARA),
                "1.20", null, "Fragile: glass jars.", createdThisMorning, Journey.CREATED, now);
        DeliverySnapshot p2 = parcel(2, handlooms,
                contact("Shalini Fernando", "+94760000202", "7 Akuressa Road", "Akuressa", District.MATARA),
                "0.80", "1500.00", null, createdThisMorning.plus(Duration.ofMinutes(20)), Journey.CREATED, now);
        DeliverySnapshot p3 = parcel(3, bookShop,
                contact("Ruwan Silva", "+94760000203", "21 Peradeniya Road", "Kandy", District.KANDY),
                "2.50", null, null, createdThisMorning.plus(Duration.ofMinutes(40)), Journey.CREATED, now);

        // Stage 2: at the Gampaha branch since last night
        DeliverySnapshot p4 = parcel(4, spiceTraders,
                contact("Dilani Jayasuriya", "+94760000204", "3 Temple Road", "Deniyaya", District.MATARA),
                "3.00", null, null, now.minus(Duration.ofHours(30)), Journey.AT_GAMPAHA,
                now.minus(Duration.ofHours(18)));
        DeliverySnapshot p5 = parcel(5, batikHouse,
                contact("Kasun Wickramasinghe", "+94760000205", "15 Matara Road", "Hakmana", District.MATARA),
                "1.00", null, null, now.minus(Duration.ofHours(29)), Journey.AT_GAMPAHA,
                now.minus(Duration.ofHours(17)));

        // Stage 3: at the Matara branch since this morning
        DeliverySnapshot p6 = parcel(6, handlooms,
                contact("Anoma Rathnayake", "+94760000206", "12 Tea Estate Road", "Deniyaya", District.MATARA),
                "0.50", "2450.00", "Call before you arrive.", now.minus(Duration.ofHours(54)), Journey.AT_MATARA,
                now.minus(Duration.ofHours(6)));
        DeliverySnapshot p7 = parcel(7, spiceTraders,
                contact("Tharindu Bandara", "+94760000207", "52 Station Road", "Morawaka", District.MATARA),
                "1.50", null, null, now.minus(Duration.ofHours(53)), Journey.AT_MATARA,
                now.minus(Duration.ofHours(6)));
        DeliverySnapshot p8 = parcel(8, batikHouse,
                contact("Chamari Peiris", "+94760000208", "4 Lake Drive", "Kotapola", District.MATARA),
                "2.00", "990.00", "Leave with the shop next door if closed.", now.minus(Duration.ofHours(52)),
                Journey.AT_MATARA, now.minus(Duration.ofHours(5)));

        // Another courier's parcel, on its way to Matara
        DeliverySnapshot p9 = parcel(9, bookShop,
                contact("Sunil Fernando", "+94760000209", "8 Kiriweldola Road", "Deniyaya", District.MATARA),
                "1.10", null, null, now.minus(Duration.ofHours(28)), Journey.IN_TRANSIT,
                now.minus(Duration.ofMinutes(40)));

        List<DeliverySnapshot> parcels = List.of(p1, p2, p3, p4, p5, p6, p7, p8, p9);
        List<AssignmentView> tasks = List.of(
                assignment(courierId, AssignmentType.PICKUP, null, District.GAMPAHA, today,
                        "Morning round around Delgoda. Hand everything over at the Gampaha branch.",
                        now.minus(Duration.ofHours(2)), List.of(p1, p2, p3)),
                assignment(courierId, AssignmentType.TRANSFER, District.GAMPAHA, District.MATARA, today,
                        "Gampaha to Matara run.", now.minus(Duration.ofMinutes(90)), List.of(p4, p5)),
                assignment(courierId, AssignmentType.DELIVERY, District.MATARA, null, today,
                        "Deniyaya area round from the Matara branch.", now.minus(Duration.ofHours(1)),
                        List.of(p6, p7, p8)),
                loadedTransfer(otherCourierId, today, now.minus(Duration.ofHours(1)), p9));

        for (DeliverySnapshot parcel : parcels) {
            deliveries.put(parcel, history(parcel));
        }
        tasks.forEach(assignments::put);
        return new Seeded(parcels.size(), tasks.size());
    }

    /** The steps a demo parcel has taken so far, with the branch of each AT_BRANCH step. */
    private enum Journey {
        CREATED(List.of(ParcelStatus.CREATED), List.of()),
        AT_GAMPAHA(List.of(ParcelStatus.CREATED, ParcelStatus.PICKED_UP, ParcelStatus.AT_BRANCH),
                List.of(District.GAMPAHA)),
        IN_TRANSIT(List.of(ParcelStatus.CREATED, ParcelStatus.PICKED_UP, ParcelStatus.AT_BRANCH,
                ParcelStatus.IN_TRANSIT), List.of(District.GAMPAHA)),
        AT_MATARA(List.of(ParcelStatus.CREATED, ParcelStatus.PICKED_UP, ParcelStatus.AT_BRANCH,
                ParcelStatus.IN_TRANSIT, ParcelStatus.AT_BRANCH), List.of(District.GAMPAHA, District.MATARA));

        final List<ParcelStatus> steps;
        /** The branch of each AT_BRANCH step, in order. */
        final List<District> branches;

        Journey(List<ParcelStatus> steps, List<District> branches) {
            this.steps = steps;
            this.branches = branches;
        }

        ParcelStatus status() {
            return steps.getLast();
        }

        District currentBranch() {
            return status() == ParcelStatus.AT_BRANCH ? branches.getLast() : null;
        }
    }

    private static DeliverySnapshot parcel(int number, DeliveryContact pickup, DeliveryContact recipient,
                                           String weightKg, String codAmount, String instructions, Instant createdAt,
                                           Journey journey, Instant lastChange) {
        Instant updatedAt = journey == Journey.CREATED ? createdAt : lastChange;
        return new DeliverySnapshot(UUID.randomUUID(), String.format("SD26110600%02d", number), journey.status(),
                null, UUID.randomUUID(), pickup, recipient, new BigDecimal(weightKg),
                codAmount == null ? null : new BigDecimal(codAmount), instructions, null, null, createdAt, updatedAt,
                null, false, null, "DEMO-" + number, new BigDecimal("450.00"), "LKR", journey.steps.size(),
                journey.currentBranch());
    }

    /** The parcel's journey so far, spread evenly between its creation and its last change, newest first. */
    private static List<ParcelStatusEntry> history(DeliverySnapshot parcel) {
        Journey journey = journeyOf(parcel);
        List<ParcelStatus> steps = journey.steps;
        Duration step = steps.size() > 1
                ? Duration.between(parcel.createdAt(), parcel.updatedAt()).dividedBy(steps.size() - 1)
                : Duration.ZERO;
        List<ParcelStatusEntry> entries = new ArrayList<>();
        int branchIndex = 0;
        for (int i = 0; i < steps.size(); i++) {
            ParcelStatus status = steps.get(i);
            District branch = status == ParcelStatus.AT_BRANCH ? journey.branches.get(branchIndex++) : null;
            Instant at = i == steps.size() - 1 ? parcel.updatedAt() : parcel.createdAt().plus(step.multipliedBy(i));
            entries.add(new ParcelStatusEntry(status, null, null, i == 0 ? "MERCHANT" : "COURIER", at, branch));
        }
        Collections.reverse(entries);
        return entries;
    }

    private static Journey journeyOf(DeliverySnapshot parcel) {
        for (Journey journey : Journey.values()) {
            if (journey.status() == parcel.status() && journey.currentBranch() == parcel.currentBranch()) {
                return journey;
            }
        }
        throw new IllegalStateException("No demo journey for " + parcel.status());
    }

    private static AssignmentView assignment(UUID courierId, AssignmentType type, District origin,
                                             District destination, LocalDate today, String note, Instant assignedAt,
                                             List<DeliverySnapshot> parcels) {
        UUID id = UUID.randomUUID();
        List<AssignmentItemView> items = new ArrayList<>();
        for (int i = 0; i < parcels.size(); i++) {
            items.add(new AssignmentItemView(id, parcels.get(i).id(), i + 1, ItemOutcome.PENDING, null, null, null,
                    null, null, assignedAt, null));
        }
        return new AssignmentView(id, courierId, type, AssignmentStatus.ASSIGNED, origin, destination, today, note,
                assignedAt, null, null, null, items, 0);
    }

    private static AssignmentView loadedTransfer(UUID courierId, LocalDate today, Instant assignedAt,
                                                 DeliverySnapshot parcel) {
        UUID id = UUID.randomUUID();
        Instant loadedAt = parcel.updatedAt();
        return new AssignmentView(id, courierId, AssignmentType.TRANSFER, AssignmentStatus.IN_PROGRESS,
                District.GAMPAHA, District.MATARA, today, null, assignedAt, loadedAt, null, null,
                List.of(new AssignmentItemView(id, parcel.id(), 1, ItemOutcome.LOADED, null, null, null, null, null,
                        loadedAt, null)),
                1);
    }

    private static DeliveryContact contact(String name, String phone, String address, String city,
                                           District district) {
        return new DeliveryContact(name, phone, address, city, district);
    }
}
