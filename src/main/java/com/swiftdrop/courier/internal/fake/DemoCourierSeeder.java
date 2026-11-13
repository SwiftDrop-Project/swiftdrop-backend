// TEMPORARY - delete when WP8 implements AssignmentApi.
package com.swiftdrop.courier.internal.fake;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * TEMPORARY: the multi-branch demo (Delgoda → Gampaha branch → Matara branch → Deniyaya) for the demo courier
 * ({@code courier@swiftdrop.lk}), on only with {@code SWIFTDROP_FAKE_DISPATCH=true}.
 *
 * <ul>
 *   <li>The parcels are real: created through {@link DeliveryApi} as orders of the demo merchant
 *       ({@code merchant@swiftdrop.lk}), then moved to their starting status as another courier would have.</li>
 *   <li>The tasks are in memory, in {@link FakeAssignmentApi}: a restart forgets them.</li>
 *   <li>So every start creates a new set of parcels. Their merchant order refs carry the start time
 *       ({@code WP9-DEMO-<run>-<n>}), so a restart never gets back parcels an earlier walkthrough already moved.
 *       Older demo parcels stay in the database without tasks.</li>
 *   <li>Only on a local database (host {@code localhost}, {@code 127.0.0.1} or {@code postgres}): with any other
 *       database, e.g. the team's shared Neon database, it logs a warning and creates nothing.</li>
 * </ul>
 * Needs both demo accounts ({@code SEED_DEMO_USERS=true}), so it runs after the account seeders. A failure is logged
 * and the app keeps starting, without demo tasks. Sample data only: no real people.
 */
@Component
@Order(3)
@ConditionalOnProperty(name = "swiftdrop.dev.fake-dispatch", havingValue = "true")
class DemoCourierSeeder implements ApplicationRunner {

    static final String DEMO_COURIER_EMAIL = "courier@swiftdrop.lk";
    static final String DEMO_MERCHANT_EMAIL = "merchant@swiftdrop.lk";
    /** The courier who did the steps before the demo courier's tasks, and who holds the other TRANSFER. No account. */
    static final UUID DEMO_OTHER_COURIER = UUID.fromString("9e1d2c3b-4a5f-4e6d-8c7b-000000000009");
    static final String ORDER_REF_PREFIX = "WP9-DEMO-";
    /** Database hosts on the developer's own machine: the laptop itself, or the Compose service name. */
    static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "postgres");

    private static final Logger log = LoggerFactory.getLogger(DemoCourierSeeder.class);
    private static final ZoneId SRI_LANKA = ZoneId.of("Asia/Colombo");
    /** Down to the millisecond, so two app contexts started in the same second (tests) never share refs. */
    private static final DateTimeFormatter RUN_FORMAT = DateTimeFormatter.ofPattern("yyMMddHHmmssSSS");
    private static final String ACTOR_ROLE = "COURIER";

    /** What one start created. {@code parcels} by demo number 1-9; parcel 9 is in the other courier's task. */
    record DemoRun(String run, Map<Integer, DeliverySnapshot> parcels, List<AssignmentView> assignments) {

        DeliverySnapshot parcel(int number) {
            DeliverySnapshot parcel = parcels.get(number);
            if (parcel == null) {
                throw new IllegalArgumentException("No demo parcel " + number);
            }
            return parcel;
        }

        UUID parcelId(int number) {
            return parcel(number).id();
        }
    }

    /** Where a demo parcel starts, and the steps that take a new parcel there. */
    private enum Stage {
        WAITING_FOR_PICKUP(List.of()),
        AT_GAMPAHA(List.of(step(ParcelStatus.PICKED_UP), branch(District.GAMPAHA))),
        TO_MATARA(List.of(step(ParcelStatus.PICKED_UP), branch(District.GAMPAHA), step(ParcelStatus.IN_TRANSIT))),
        AT_MATARA(List.of(step(ParcelStatus.PICKED_UP), branch(District.GAMPAHA), step(ParcelStatus.IN_TRANSIT),
                branch(District.MATARA)));

        final List<Step> steps;

        Stage(List<Step> steps) {
            this.steps = steps;
        }
    }

    private record Step(ParcelStatus status, District branch) {
    }

    private static Step step(ParcelStatus status) {
        return new Step(status, null);
    }

    private static Step branch(District branch) {
        return new Step(ParcelStatus.AT_BRANCH, branch);
    }

    private record DemoParcel(int number, DeliveryContact pickup, DeliveryContact recipient, String weightKg,
                              String codAmount, String instructions, Stage stage) {
    }

    private static final DeliveryContact SPICE_TRADERS = contact("Delgoda Spice Traders", "+94760000101",
            "14 Kandy Road", "Delgoda", District.GAMPAHA);
    private static final DeliveryContact HANDLOOMS = contact("Kirindiwita Handlooms", "+94760000102",
            "3 Temple Lane", "Kirindiwita", District.GAMPAHA);
    private static final DeliveryContact BOOK_SHOP = contact("Weliweriya Book Shop", "+94760000103",
            "27 Main Street", "Weliweriya", District.GAMPAHA);
    private static final DeliveryContact BATIK_HOUSE = contact("Ganemulla Batik House", "+94760000104",
            "9 Station Road", "Ganemulla", District.GAMPAHA);

    private static final List<DemoParcel> DEMO_PARCELS = List.of(
            // PICKUP around Delgoda, to the Gampaha branch
            new DemoParcel(1, SPICE_TRADERS,
                    contact("Nimal Perera", "+94760000201", "45 Rakwana Road", "Deniyaya", District.MATARA),
                    "1.20", null, "Fragile: glass jars.", Stage.WAITING_FOR_PICKUP),
            new DemoParcel(2, HANDLOOMS,
                    contact("Shalini Fernando", "+94760000202", "7 Akuressa Road", "Akuressa", District.MATARA),
                    "0.80", "1500.00", null, Stage.WAITING_FOR_PICKUP),
            new DemoParcel(3, BOOK_SHOP,
                    contact("Ruwan Silva", "+94760000203", "21 Peradeniya Road", "Kandy", District.KANDY),
                    "2.50", null, null, Stage.WAITING_FOR_PICKUP),
            // TRANSFER Gampaha → Matara
            new DemoParcel(4, SPICE_TRADERS,
                    contact("Dilani Jayasuriya", "+94760000204", "3 Temple Road", "Deniyaya", District.MATARA),
                    "3.00", null, null, Stage.AT_GAMPAHA),
            new DemoParcel(5, BATIK_HOUSE,
                    contact("Kasun Wickramasinghe", "+94760000205", "15 Matara Road", "Hakmana", District.MATARA),
                    "1.00", null, null, Stage.AT_GAMPAHA),
            // DELIVERY from the Matara branch
            new DemoParcel(6, HANDLOOMS,
                    contact("Anoma Rathnayake", "+94760000206", "12 Tea Estate Road", "Deniyaya", District.MATARA),
                    "0.50", "2450.00", "Call before you arrive.", Stage.AT_MATARA),
            new DemoParcel(7, SPICE_TRADERS,
                    contact("Tharindu Bandara", "+94760000207", "52 Station Road", "Morawaka", District.MATARA),
                    "1.50", null, null, Stage.AT_MATARA),
            new DemoParcel(8, BATIK_HOUSE,
                    contact("Chamari Peiris", "+94760000208", "4 Lake Drive", "Kotapola", District.MATARA),
                    "2.00", "990.00", "Leave with the shop next door if closed.", Stage.AT_MATARA),
            // the other courier's parcel, on its way to Matara
            new DemoParcel(9, BOOK_SHOP,
                    contact("Sunil Fernando", "+94760000209", "8 Kiriweldola Road", "Deniyaya", District.MATARA),
                    "1.10", null, null, Stage.TO_MATARA));

    private final DeliveryApi deliveries;
    private final FakeAssignmentApi assignments;
    private final IdentityApi identity;
    private final DataSource dataSource;
    private final ListableBeanFactory beans;
    private final Clock clock;
    private volatile DemoRun lastRun;

    @Autowired
    DemoCourierSeeder(DeliveryApi deliveries, FakeAssignmentApi assignments, IdentityApi identity,
                      DataSource dataSource, ListableBeanFactory beans) {
        this(deliveries, assignments, identity, dataSource, beans, Clock.systemUTC());
    }

    DemoCourierSeeder(DeliveryApi deliveries, FakeAssignmentApi assignments, IdentityApi identity,
                      DataSource dataSource, ListableBeanFactory beans, Clock clock) {
        this.deliveries = deliveries;
        this.assignments = assignments;
        this.identity = identity;
        this.dataSource = dataSource;
        this.beans = beans;
        this.clock = clock;
    }

    /** What the last start created, or null when it created nothing. For tests. */
    DemoRun lastRun() {
        return lastRun;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (beans.getBeanNamesForType(AssignmentApi.class).length > 1) {
            log.warn("SWIFTDROP_FAKE_DISPATCH is on but a real AssignmentApi exists: the fake is used. Turn it off.");
        }
        String jdbcUrl = jdbcUrl();
        if (!isLocalDatabase(jdbcUrl)) {
            log.warn("SWIFTDROP_FAKE_DISPATCH is on, but the database is not local (host {}): demo courier tasks "
                    + "are only created on a local database. Skipped, nothing was written.",
                    Objects.requireNonNullElse(hostOf(jdbcUrl), "unknown"));
            return;
        }
        Optional<UserSummary> courier = account(DEMO_COURIER_EMAIL, Role.COURIER);
        Optional<UserSummary> merchant = account(DEMO_MERCHANT_EMAIL, Role.MERCHANT);
        if (courier.isEmpty() || merchant.isEmpty()) {
            log.warn("SWIFTDROP_FAKE_DISPATCH is on but the demo courier or demo merchant account doesn't exist "
                    + "(set SEED_DEMO_USERS=true): no demo tasks.");
            return;
        }
        if (!assignments.isEmpty()) {
            return;
        }
        try {
            DemoRun run = seed(deliveries, assignments, courier.get().id(), merchant.get().id(), clock.instant());
            lastRun = run;
            log.warn("SWIFTDROP_FAKE_DISPATCH is on: {} demo parcels (saved in the local database) and {} demo tasks "
                    + "(in memory, gone after a restart) for {}.", run.parcels().size(), run.assignments().size(),
                    DEMO_COURIER_EMAIL);
            for (DemoParcel demo : DEMO_PARCELS) {
                log.info("Demo parcel {}: {} ({})", demo.number(), run.parcel(demo.number()).trackingNumber(),
                        taskOf(demo.number()));
            }
        } catch (RuntimeException ex) {
            log.error("Couldn't create the demo courier tasks; the app starts without them.", ex);
        }
    }

    /**
     * Creates the demo parcels for this run, moves each to its starting status, and puts the four tasks (three for
     * {@code courierId}, one for {@link #DEMO_OTHER_COURIER}) into {@code assignments}. All tasks are planned for
     * today.
     */
    static DemoRun seed(DeliveryApi deliveries, FakeAssignmentApi assignments, UUID courierId, UUID merchantId,
                        Instant now) {
        String run = RUN_FORMAT.format(now.atZone(SRI_LANKA));
        Map<Integer, DeliverySnapshot> parcels = new LinkedHashMap<>();
        for (DemoParcel demo : DEMO_PARCELS) {
            DeliverySnapshot created = deliveries.create(new CreateDeliveryCommand(null, merchantId,
                    orderRef(run, demo.number()), demo.pickup(), demo.recipient(), new BigDecimal(demo.weightKg()),
                    demo.codAmount() == null ? null : new BigDecimal(demo.codAmount()), demo.instructions()));
            if (created.status() != ParcelStatus.CREATED) {
                throw new IllegalStateException("Demo order " + orderRef(run, demo.number())
                        + " already exists and has moved on");
            }
            parcels.put(demo.number(), moveTo(deliveries, created, demo.stage()));
        }
        Map<Integer, UUID> ids = new LinkedHashMap<>();
        parcels.forEach((number, parcel) -> ids.put(number, parcel.id()));
        List<AssignmentView> tasks = assignments(courierId, DEMO_OTHER_COURIER,
                now.atZone(SRI_LANKA).toLocalDate(), now, ids);
        tasks.forEach(assignments::put);
        return new DemoRun(run, Map.copyOf(parcels), tasks);
    }

    /**
     * The four demo tasks for these parcel ids (demo numbers 1-9), all planned for {@code today}:
     * <ul>
     *   <li>PICKUP to the Gampaha branch: parcels 1-3</li>
     *   <li>TRANSFER Gampaha → Matara: parcels 4-5</li>
     *   <li>DELIVERY from the Matara branch: parcels 6-8</li>
     *   <li>the other courier's TRANSFER, parcel 9 already loaded (the demo courier must not see it)</li>
     * </ul>
     * No delivery calls, so tests of {@link FakeAssignmentApi} can use it with any ids.
     */
    static List<AssignmentView> assignments(UUID courierId, UUID otherCourierId, LocalDate today, Instant now,
                                            Map<Integer, UUID> parcelIds) {
        return List.of(
                assignment(courierId, AssignmentType.PICKUP, null, District.GAMPAHA, today,
                        "Morning round around Delgoda. Hand everything over at the Gampaha branch.",
                        now.minus(Duration.ofHours(2)), ids(parcelIds, 1, 2, 3)),
                assignment(courierId, AssignmentType.TRANSFER, District.GAMPAHA, District.MATARA, today,
                        "Gampaha to Matara run.", now.minus(Duration.ofMinutes(90)), ids(parcelIds, 4, 5)),
                assignment(courierId, AssignmentType.DELIVERY, District.MATARA, null, today,
                        "Deniyaya area round from the Matara branch.", now.minus(Duration.ofHours(1)),
                        ids(parcelIds, 6, 7, 8)),
                loadedTransfer(otherCourierId, today, now.minus(Duration.ofHours(1)), now, parcelIds.get(9)));
    }

    /** {@code WP9-DEMO-<run>-<number>}: new for every start, at most 64 characters (the column's size). */
    static String orderRef(String run, int number) {
        return ORDER_REF_PREFIX + run + "-" + number;
    }

    /**
     * Whether the JDBC URL points to a database on this machine: host {@code localhost}, {@code 127.0.0.1} or the
     * Compose service {@code postgres}. Anything else, including a URL that can't be read, counts as not local.
     */
    static boolean isLocalDatabase(String jdbcUrl) {
        String host = hostOf(jdbcUrl);
        return host != null && LOCAL_HOSTS.contains(host);
    }

    /** The host of a JDBC URL in lower case, or null when there is none (or it can't be read). */
    static String hostOf(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
            return null;
        }
        try {
            String host = URI.create(jdbcUrl.substring("jdbc:".length())).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** The URL of the database the app really writes to (in tests, the Testcontainers one), or null. */
    private String jdbcUrl() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        } catch (SQLException ex) {
            log.warn("Couldn't read the database URL: {}", ex.getMessage());
            return null;
        }
    }

    private Optional<UserSummary> account(String email, Role role) {
        return identity.findByEmail(email).filter(user -> user.role() == role);
    }

    private static String taskOf(int number) {
        if (number <= 3) {
            return "PICKUP";
        }
        if (number <= 5) {
            return "TRANSFER";
        }
        return number <= 8 ? "DELIVERY" : "another courier's TRANSFER";
    }

    /** Takes a new parcel through the steps of {@code stage}, as the other courier. Returns the parcel after them. */
    private static DeliverySnapshot moveTo(DeliveryApi deliveries, DeliverySnapshot parcel, Stage stage) {
        DeliverySnapshot current = parcel;
        for (Step step : stage.steps) {
            current = deliveries.changeStatus(new ChangeStatusCommand(parcel.id(), step.status(), DEMO_OTHER_COURIER,
                    ACTOR_ROLE, null, null, null, null, step.branch()));
        }
        return current;
    }

    private static List<UUID> ids(Map<Integer, UUID> parcelIds, int... numbers) {
        List<UUID> ids = new ArrayList<>();
        for (int number : numbers) {
            UUID id = parcelIds.get(number);
            if (id == null) {
                throw new IllegalArgumentException("No id for demo parcel " + number);
            }
            ids.add(id);
        }
        return ids;
    }

    private static AssignmentView assignment(UUID courierId, AssignmentType type, District origin,
                                             District destination, LocalDate today, String note, Instant assignedAt,
                                             List<UUID> parcelIds) {
        UUID id = UUID.randomUUID();
        List<AssignmentItemView> items = new ArrayList<>();
        for (int i = 0; i < parcelIds.size(); i++) {
            items.add(new AssignmentItemView(id, parcelIds.get(i), i + 1, ItemOutcome.PENDING, null, null, null,
                    null, null, assignedAt, null));
        }
        return new AssignmentView(id, courierId, type, AssignmentStatus.ASSIGNED, origin, destination, today, note,
                assignedAt, null, null, null, items, 0);
    }

    private static AssignmentView loadedTransfer(UUID courierId, LocalDate today, Instant assignedAt,
                                                 Instant loadedAt, UUID parcelId) {
        if (parcelId == null) {
            throw new IllegalArgumentException("No id for demo parcel 9");
        }
        UUID id = UUID.randomUUID();
        return new AssignmentView(id, courierId, AssignmentType.TRANSFER, AssignmentStatus.IN_PROGRESS,
                District.GAMPAHA, District.MATARA, today, null, assignedAt, loadedAt, null, null,
                List.of(new AssignmentItemView(id, parcelId, 1, ItemOutcome.LOADED, null, null, null, null, null,
                        loadedAt, null)),
                1);
    }

    private static DeliveryContact contact(String name, String phone, String address, String city,
                                           District district) {
        return new DeliveryContact(name, phone, address, city, district);
    }
}
