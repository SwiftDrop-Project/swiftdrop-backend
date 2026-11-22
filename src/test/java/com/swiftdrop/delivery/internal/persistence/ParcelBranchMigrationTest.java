package com.swiftdrop.delivery.internal.persistence;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.ChangeStatusCommand;
import com.swiftdrop.delivery.CreateDeliveryCommand;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.delivery.ParcelStatusEntry;
import com.swiftdrop.delivery.internal.domain.Parcel;
import com.swiftdrop.delivery.internal.domain.ParcelStatusHistory;
import com.swiftdrop.shared.reference.District;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.swiftdrop.delivery.ParcelStatus.AT_BRANCH;
import static com.swiftdrop.delivery.ParcelStatus.PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V202611081903__add_branch_to_parcels against the real PostgreSQL (Testcontainers): a parcel and its history can be
 * saved in AT_BRANCH with a branch, and the new CHECK keeps the branch and the status in step.
 */
class ParcelBranchMigrationTest extends IntegrationTest {

    private static final Instant BOOKED = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final DeliveryContact PICKUP =
            new DeliveryContact("Nimal Perera", "+94771234567", "12 Main Street", "Delgoda", District.GAMPAHA);
    private static final DeliveryContact RECIPIENT =
            new DeliveryContact("Kamala Silva", "+94719876543", "45 Temple Road", "Deniyaya", District.MATARA);

    @Autowired
    ParcelRepository parcels;

    @Autowired
    ParcelStatusHistoryRepository history;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aParcelAndItsHistoryAreSavedAtABranch() {
        Parcel parcel = newParcel();
        ChangeStatusCommand pickedUp = command(parcel, PICKED_UP, null);
        ChangeStatusCommand atGampaha = command(parcel, AT_BRANCH, District.GAMPAHA);
        parcel.changeStatus(pickedUp, BOOKED.plusSeconds(60));
        ParcelStatus from = parcel.changeStatus(atGampaha, BOOKED.plusSeconds(120));

        parcels.saveAndFlush(parcel);
        history.saveAndFlush(ParcelStatusHistory.firstEntry(UUID.randomUUID(), parcel));
        history.saveAndFlush(ParcelStatusHistory.forChange(UUID.randomUUID(), from, atGampaha,
                BOOKED.plusSeconds(120)));

        Parcel saved = parcels.findById(parcel.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(AT_BRANCH);
        assertThat(saved.getCurrentBranch()).isEqualTo(District.GAMPAHA);
        assertThat(saved.toSnapshot().currentBranch()).isEqualTo(District.GAMPAHA);
        assertThat(jdbc.queryForObject("select current_branch from delivery.parcels where id = ?", String.class,
                parcel.getId())).isEqualTo("GAMPAHA");

        List<ParcelStatusEntry> entries = history.findByParcelIdOrderByOccurredAtDesc(parcel.getId()).stream()
                .map(ParcelStatusHistory::toEntry).toList();
        assertThat(entries).extracting(ParcelStatusEntry::status).containsExactly(AT_BRANCH, ParcelStatus.CREATED);
        assertThat(entries.get(0).branch()).isEqualTo(District.GAMPAHA);
        assertThat(entries.get(1).branch()).isNull();
    }

    @Test
    void atBranchWithoutABranchIsRejected() {
        Parcel parcel = newParcel();
        parcels.saveAndFlush(parcel);

        assertThatThrownBy(() -> jdbc.update(
                "update delivery.parcels set status = 'AT_BRANCH', current_branch = null where id = ?",
                parcel.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_parcels_current_branch");
    }

    @Test
    void aBranchWithAnotherStatusIsRejected() {
        Parcel parcel = newParcel();
        parcels.saveAndFlush(parcel);

        assertThatThrownBy(() -> jdbc.update(
                "update delivery.parcels set current_branch = 'GAMPAHA' where id = ?", parcel.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_parcels_current_branch");
    }

    @Test
    void anUnknownStatusIsStillRejected() {
        Parcel parcel = newParcel();
        parcels.saveAndFlush(parcel);

        assertThatThrownBy(() -> jdbc.update(
                "update delivery.parcels set status = 'LOST' where id = ?", parcel.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_parcels_status");
    }

    @Test
    void theAtBranchIndexExists() {
        assertThat(jdbc.queryForObject("""
                select indexdef from pg_indexes
                where schemaname = 'delivery' and indexname = 'idx_parcels_at_branch'""", String.class))
                .contains("(current_branch, status_changed_at)")
                .contains("'AT_BRANCH'");
    }

    private static Parcel newParcel() {
        var command = new CreateDeliveryCommand(UUID.randomUUID(), null, null, PICKUP, RECIPIENT,
                new BigDecimal("1.20"), null, null);
        String trackingNumber = String.format("SD2611%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        return Parcel.create(UUID.randomUUID(), trackingNumber, command, new BigDecimal("450.00"), "LKR", BOOKED);
    }

    private static ChangeStatusCommand command(Parcel parcel, ParcelStatus to, District branch) {
        return new ChangeStatusCommand(parcel.getId(), to, UUID.randomUUID(), "COURIER", null, null, null, null,
                branch);
    }
}
