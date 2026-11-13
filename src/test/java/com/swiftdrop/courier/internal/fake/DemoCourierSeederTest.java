// TEMPORARY - delete with the courier fakes.
package com.swiftdrop.courier.internal.fake;

import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentApi;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ListableBeanFactory;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DemoCourierSeederTest {

    private static final Instant NOW = Instant.parse("2026-11-10T04:30:05.123Z"); // 10:00:05.123 in Colombo
    private static final String NEON = "jdbc:postgresql://ep-cool-sound-123456.ap-southeast-1.aws.neon.tech/swiftdrop"
            + "?sslmode=require";
    private static final String LOCAL = "jdbc:postgresql://localhost:5432/swiftdrop";

    private final UUID courierId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private DeliveryApi deliveries;
    private FakeAssignmentApi assignments;
    private IdentityApi identity;
    private ListableBeanFactory beans;

    @BeforeEach
    void setUp() {
        deliveries = mock(DeliveryApi.class);
        assignments = new FakeAssignmentApi(Clock.fixed(NOW, ZoneOffset.UTC));
        identity = mock(IdentityApi.class);
        beans = mock(ListableBeanFactory.class);
        when(beans.getBeanNamesForType(AssignmentApi.class)).thenReturn(new String[]{"fakeAssignmentApi"});
        // a fake delivery module that does what it is told
        when(deliveries.create(any())).thenAnswer(call -> snapshot(UUID.randomUUID(), ParcelStatus.CREATED, null));
        when(deliveries.changeStatus(any())).thenAnswer(call -> {
            ChangeStatusCommand command = call.getArgument(0);
            return snapshot(command.parcelId(), command.toStatus(), command.branch());
        });
    }

    // ---- The local-database guard ----

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432/swiftdrop",
            "jdbc:postgresql://localhost/swiftdrop",
            "jdbc:postgresql://LOCALHOST:5432/swiftdrop?sslmode=disable",
            "jdbc:postgresql://127.0.0.1:32768/test?loggerLevel=OFF",
            "jdbc:postgresql://postgres:5432/swiftdrop"})
    void aDatabaseOnThisMachineIsLocal(String url) {
        assertThat(DemoCourierSeeder.isLocalDatabase(url)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            NEON,
            "jdbc:postgresql://tests-must-use-testcontainers.invalid:5432/none",
            "jdbc:postgresql://db.example.com:5432/swiftdrop",
            "jdbc:postgresql://localhost.example.com/swiftdrop",
            "jdbc:postgresql://host1:5432,localhost:5433/swiftdrop",
            "postgresql://localhost:5432/swiftdrop",
            "not a url",
            ""})
    void anyOtherDatabaseIsNotLocal(String url) {
        assertThat(DemoCourierSeeder.isLocalDatabase(url)).isFalse();
    }

    @Test
    void aMissingUrlIsNotLocal() {
        assertThat(DemoCourierSeeder.isLocalDatabase(null)).isFalse();
    }

    @Test
    void onTheSharedDatabaseNothingIsWritten() throws Exception {
        DemoCourierSeeder seeder = seeder(NEON);
        accountsExist();

        seeder.run(null);

        verifyNoInteractions(deliveries);
        assertThat(assignments.isEmpty()).isTrue();
        assertThat(seeder.lastRun()).isNull();
    }

    @Test
    void whenTheDatabaseUrlCantBeReadNothingIsWritten() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
        DemoCourierSeeder seeder = new DemoCourierSeeder(deliveries, assignments, identity, dataSource, beans,
                Clock.fixed(NOW, ZoneOffset.UTC));
        accountsExist();

        seeder.run(null);

        verifyNoInteractions(deliveries);
        assertThat(assignments.isEmpty()).isTrue();
    }

    @Test
    void withoutTheDemoAccountsNothingIsWritten() throws Exception {
        DemoCourierSeeder seeder = seeder(LOCAL);
        when(identity.findByEmail(any())).thenReturn(Optional.empty());

        seeder.run(null);

        verifyNoInteractions(deliveries);
        assertThat(assignments.isEmpty()).isTrue();
    }

    // ---- Seeding ----

    @Test
    void onALocalDatabaseItCreatesNineOrdersMovesThemToTheirStageAndAddsTheTasks() throws Exception {
        DemoCourierSeeder seeder = seeder(LOCAL);
        accountsExist();

        seeder.run(null);

        ArgumentCaptor<CreateDeliveryCommand> created = ArgumentCaptor.forClass(CreateDeliveryCommand.class);
        verify(deliveries, times(9)).create(created.capture());
        assertThat(created.getAllValues()).allSatisfy(command -> {
            assertThat(command.merchantId()).isEqualTo(merchantId);
            assertThat(command.customerId()).isNull();
        });
        assertThat(created.getAllValues()).extracting(CreateDeliveryCommand::merchantOrderRef)
                .containsExactly("WP9-DEMO-261110100005123-1", "WP9-DEMO-261110100005123-2",
                        "WP9-DEMO-261110100005123-3", "WP9-DEMO-261110100005123-4", "WP9-DEMO-261110100005123-5",
                        "WP9-DEMO-261110100005123-6", "WP9-DEMO-261110100005123-7", "WP9-DEMO-261110100005123-8",
                        "WP9-DEMO-261110100005123-9");
        // 4-5: picked up, at Gampaha (2 each); 6-8: + in transit, at Matara (4 each); 9: in transit (3)
        ArgumentCaptor<ChangeStatusCommand> moves = ArgumentCaptor.forClass(ChangeStatusCommand.class);
        verify(deliveries, times(19)).changeStatus(moves.capture());
        assertThat(moves.getAllValues()).allSatisfy(command -> {
            assertThat(command.actorId()).isEqualTo(DemoCourierSeeder.DEMO_OTHER_COURIER);
            assertThat(command.actorRole()).isEqualTo("COURIER");
        });

        DemoCourierSeeder.DemoRun run = seeder.lastRun();
        assertThat(run.run()).isEqualTo("261110100005123");
        assertThat(run.parcel(1).status()).isEqualTo(ParcelStatus.CREATED);
        assertThat(run.parcel(4).currentBranch()).isEqualTo(District.GAMPAHA);
        assertThat(run.parcel(6).currentBranch()).isEqualTo(District.MATARA);
        assertThat(run.parcel(9).status()).isEqualTo(ParcelStatus.IN_TRANSIT);

        List<AssignmentView> mine = assignments.findOpenForCourier(courierId, Instant.EPOCH);
        assertThat(mine).extracting(AssignmentView::type)
                .containsExactlyInAnyOrder(AssignmentType.PICKUP, AssignmentType.TRANSFER, AssignmentType.DELIVERY);
        assertThat(assignments.findOpenForCourier(DemoCourierSeeder.DEMO_OTHER_COURIER, Instant.EPOCH)).hasSize(1);
    }

    @Test
    void itSeedsOnlyOncePerStart() throws Exception {
        DemoCourierSeeder seeder = seeder(LOCAL);
        accountsExist();

        seeder.run(null);
        seeder.run(null);

        verify(deliveries, times(9)).create(any());
    }

    @Test
    void anOrderThatAlreadyMovedOnStopsTheSeeding() {
        when(deliveries.create(any())).thenAnswer(call -> snapshot(UUID.randomUUID(), ParcelStatus.DELIVERED, null));

        assertThatThrownBy(() -> DemoCourierSeeder.seed(deliveries, assignments, courierId, merchantId, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
        assertThat(assignments.isEmpty()).isTrue();
    }

    @Test
    void aFailureIsLoggedAndTheAppKeepsStarting() throws Exception {
        DemoCourierSeeder seeder = seeder(LOCAL);
        accountsExist();
        when(deliveries.create(any())).thenThrow(new IllegalStateException("database is down"));

        seeder.run(null);

        assertThat(seeder.lastRun()).isNull();
        assertThat(assignments.isEmpty()).isTrue();
    }

    @Test
    void orderRefsFitTheColumn() {
        String longestRun = "999999999999999";
        assertThat(DemoCourierSeeder.orderRef(longestRun, 9)).hasSizeLessThanOrEqualTo(64)
                .startsWith(DemoCourierSeeder.ORDER_REF_PREFIX);
    }

    // ---- Helpers ----

    private DemoCourierSeeder seeder(String jdbcUrl) throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getURL()).thenReturn(jdbcUrl);
        return new DemoCourierSeeder(deliveries, assignments, identity, dataSource, beans,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void accountsExist() {
        when(identity.findByEmail(DemoCourierSeeder.DEMO_COURIER_EMAIL)).thenReturn(Optional.of(
                new UserSummary(courierId, "Demo Courier", DemoCourierSeeder.DEMO_COURIER_EMAIL, "+94770000003",
                        Role.COURIER, UserStatus.ACTIVE, NOW)));
        when(identity.findByEmail(DemoCourierSeeder.DEMO_MERCHANT_EMAIL)).thenReturn(Optional.of(
                new UserSummary(merchantId, "Demo Merchant", DemoCourierSeeder.DEMO_MERCHANT_EMAIL, "+94770000002",
                        Role.MERCHANT, UserStatus.ACTIVE, NOW)));
    }

    private DeliverySnapshot snapshot(UUID id, ParcelStatus status, District branch) {
        DeliveryContact contact = new DeliveryContact("Sample Name", "+94760000101", "1 Main Street", "Delgoda",
                District.GAMPAHA);
        return new DeliverySnapshot(id, "SD2611100001", status, null, merchantId, contact, contact,
                new BigDecimal("1.00"), null, null, null, null, NOW, NOW, null, false, null, "WP9-DEMO",
                new BigDecimal("450.00"), "LKR", 0L, branch);
    }
}
