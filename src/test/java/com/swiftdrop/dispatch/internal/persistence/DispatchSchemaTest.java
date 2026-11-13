package com.swiftdrop.dispatch.internal.persistence;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.dispatch.TaskProblem;
import com.swiftdrop.dispatch.internal.domain.AssignmentItem;
import com.swiftdrop.dispatch.internal.domain.CourierAssignment;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.swiftdrop.shared.reference.District.GAMPAHA;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V202611101200__create_dispatch_schema against the real PostgreSQL (Testcontainers): the entities match the tables
 * (ddl-auto validate already proves the columns), the constraints hold, and the repository queries work.
 * Every test uses fresh courier and parcel ids, so tests don't see each other's rows.
 */
class DispatchSchemaTest extends IntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    @Autowired
    CourierAssignmentRepository assignments;

    @Autowired
    AssignmentItemRepository items;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void anAssignmentAndItsItemsAreSavedAndReadBack() {
        UUID courier = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CourierAssignment saved = assignments.saveAndFlush(
                pickup(courier, List.of(first, second)));

        var view = transactions.execute(status -> assignments.findById(saved.getId()).orElseThrow().toView());

        assertThat(view.type()).isEqualTo(AssignmentType.PICKUP);
        assertThat(view.status()).isEqualTo(AssignmentStatus.ASSIGNED);
        assertThat(view.destinationBranch()).isEqualTo(GAMPAHA);
        assertThat(view.scheduledFor()).isEqualTo(TODAY);
        assertThat(view.assignedAt()).isEqualTo(NOW);
        assertThat(view.version()).isZero();
        assertThat(view.items()).extracting(i -> i.parcelId()).containsExactly(first, second);
        assertThat(jdbc.queryForObject("select count(*) from dispatch.assignment_items where assignment_id = ?",
                Long.class, saved.getId())).isEqualTo(2);
    }

    @Test
    void everyOutcomeRaisesTheAssignmentVersion() {
        UUID courier = UUID.randomUUID();
        UUID parcel = UUID.randomUUID();
        UUID id = assignments.saveAndFlush(pickup(courier, List.of(parcel))).getId();

        transactions.executeWithoutResult(status -> assignments.findById(id).orElseThrow().recordOutcome(
                new RecordOutcomeCommand(id, parcel, courier, ItemOutcome.COLLECTED, null, null, null, null, null),
                NOW.plusSeconds(60)));
        long afterFirst = version(id);
        transactions.executeWithoutResult(status -> assignments.findById(id).orElseThrow().recordOutcome(
                new RecordOutcomeCommand(id, parcel, courier, ItemOutcome.HANDED_OVER, null, null, null, "Sunil",
                        null), NOW.plusSeconds(120)));

        assertThat(afterFirst).isEqualTo(1);
        assertThat(version(id)).isEqualTo(2);
        var view = transactions.execute(status -> assignments.findById(id).orElseThrow().toView());
        assertThat(view.status()).isEqualTo(AssignmentStatus.IN_PROGRESS);
        assertThat(view.items().getFirst().outcome()).isEqualTo(ItemOutcome.HANDED_OVER);
        assertThat(view.items().getFirst().closedAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(view.version()).isEqualTo(2);
    }

    @Test
    void aParcelIsOpenInOnlyOneAssignment() {
        UUID parcel = UUID.randomUUID();
        assignments.saveAndFlush(pickup(UUID.randomUUID(), List.of(parcel)));

        assertThatThrownBy(() -> assignments.saveAndFlush(pickup(UUID.randomUUID(), List.of(parcel))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_assignment_items_open_parcel");
        assertThat(items.existsByParcelIdAndClosedAtIsNull(parcel)).isTrue();
    }

    @Test
    void aClosedItemLetsTheParcelBeAssignedAgain() {
        UUID courier = UUID.randomUUID();
        UUID parcel = UUID.randomUUID();
        UUID id = assignments.saveAndFlush(pickup(courier, List.of(parcel))).getId();
        transactions.executeWithoutResult(status -> assignments.findById(id).orElseThrow().recordOutcome(
                new RecordOutcomeCommand(id, parcel, courier, ItemOutcome.NOT_COLLECTED,
                        TaskProblem.SENDER_UNAVAILABLE, null, null, null, null), NOW.plusSeconds(60)));
        assertThat(items.existsByParcelIdAndClosedAtIsNull(parcel)).isFalse();

        assignments.saveAndFlush(pickup(UUID.randomUUID(), List.of(parcel)));

        assertThat(items.existsByParcelIdAndClosedAtIsNull(parcel)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from dispatch.assignment_items where parcel_id = ?",
                Long.class, parcel)).isEqualTo(2);
    }

    @Test
    void openParcelIdsLeaveOutParcelsWithNoOpenAssignment() {
        UUID open = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        assignments.saveAndFlush(pickup(UUID.randomUUID(), List.of(open)));

        assertThat(items.findOpenParcelIds(List.of(open, free))).containsExactly(open);
    }

    @Test
    void theCourierQueriesFindOpenAndClosedAssignments() {
        UUID courier = UUID.randomUUID();
        UUID openParcel = UUID.randomUUID();
        UUID closedParcel = UUID.randomUUID();
        UUID open = assignments.saveAndFlush(pickup(courier, List.of(openParcel))).getId();
        UUID closed = assignments.saveAndFlush(pickup(courier, List.of(closedParcel))).getId();
        transactions.executeWithoutResult(status -> {
            CourierAssignment assignment = assignments.findById(closed).orElseThrow();
            assignment.recordOutcome(new RecordOutcomeCommand(closed, closedParcel, courier,
                    ItemOutcome.NOT_COLLECTED, TaskProblem.PARCEL_NOT_READY, null, null, null, null), NOW);
            assignment.complete(NOW.plusSeconds(60));
        });
        assignments.saveAndFlush(pickup(UUID.randomUUID(), List.of(UUID.randomUUID())));

        assertThat(assignments.findOpenForCourier(courier, NOW.plusSeconds(3600)))
                .extracting(CourierAssignment::getId).containsExactly(open);
        assertThat(assignments.findOpenForCourier(courier, NOW))
                .extracting(CourierAssignment::getId).containsExactlyInAnyOrder(open, closed);

        Page<CourierAssignment> history = assignments.findClosedForCourier(courier, PageRequest.of(0, 20));
        assertThat(history.getContent()).extracting(CourierAssignment::getId).containsExactly(closed);
        assertThat(history.getTotalElements()).isEqualTo(1);
        assertThat(assignments.findClosedForCourierAndParcel(courier, closedParcel, PageRequest.of(0, 20))
                .getContent()).extracting(CourierAssignment::getId).containsExactly(closed);
        assertThat(assignments.findClosedForCourierAndParcel(courier, openParcel, PageRequest.of(0, 20))
                .getContent()).isEmpty();

        assertThat(assignments.findByIdAndCourierId(open, courier)).isPresent();
        assertThat(assignments.findByIdAndCourierId(open, UUID.randomUUID())).isEmpty();
    }

    @Test
    void theItemsOfAParcelAreScopedToTheCourier() {
        UUID courier = UUID.randomUUID();
        UUID parcel = UUID.randomUUID();
        UUID id = assignments.saveAndFlush(pickup(courier, List.of(parcel))).getId();

        List<AssignmentItem> found = transactions.execute(status -> {
            List<AssignmentItem> result = items.findForCourierAndParcel(courier, parcel);
            result.forEach(AssignmentItem::getAssignmentId);
            return result;
        });

        assertThat(found).extracting(AssignmentItem::getAssignmentId).containsExactly(id);
        assertThat(items.findForCourierAndParcel(UUID.randomUUID(), parcel)).isEmpty();
    }

    @Test
    void theBranchRuleIsCheckedByTheDatabaseToo() {
        UUID id = assignments.saveAndFlush(pickup(UUID.randomUUID(), List.of(UUID.randomUUID()))).getId();

        assertThatThrownBy(() -> jdbc.update(
                "update dispatch.courier_assignments set origin_branch = 'MATARA' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_courier_assignments_branches");
        assertThatThrownBy(() -> jdbc.update(
                "update dispatch.courier_assignments set type = 'TRANSFER', origin_branch = 'GAMPAHA' where id = ?",
                id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_courier_assignments_branches");
    }

    @Test
    void theTimestampsStayInStepWithTheStatus() {
        UUID id = assignments.saveAndFlush(pickup(UUID.randomUUID(), List.of(UUID.randomUUID()))).getId();

        assertThatThrownBy(() -> jdbc.update(
                "update dispatch.courier_assignments set status = 'COMPLETED' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                "update dispatch.courier_assignments set status = 'IN_PROGRESS' where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_courier_assignments_started");
        assertThatThrownBy(() -> jdbc.update(
                "update dispatch.courier_assignments set cancelled_at = now() where id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_courier_assignments_cancelled");
    }

    @Test
    void anItemKeepsOnlyTheFieldsOfItsOutcome() {
        UUID parcel = UUID.randomUUID();
        assignments.saveAndFlush(pickup(UUID.randomUUID(), List.of(parcel)));

        assertThatThrownBy(() -> jdbc.update(
                "update dispatch.assignment_items set problem = 'OTHER' where parcel_id = ?", parcel))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_assignment_items_problem_only_when_not_taken");
        assertThatThrownBy(() -> jdbc.update(
                "update dispatch.assignment_items set recipient_name = 'Kamala' where parcel_id = ?", parcel))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_assignment_items_recipient_only_when_delivered");
        assertThatThrownBy(() -> jdbc.update(
                "update dispatch.assignment_items set outcome = 'LOST' where parcel_id = ?", parcel))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_assignment_items_outcome");
    }

    @Test
    void theOpenParcelIndexIsPartial() {
        assertThat(jdbc.queryForObject("""
                select indexdef from pg_indexes
                where schemaname = 'dispatch' and indexname = 'uk_assignment_items_open_parcel'""", String.class))
                .contains("UNIQUE")
                .contains("(parcel_id)")
                .contains("closed_at IS NULL");
    }

    private static CourierAssignment pickup(UUID courier, List<UUID> parcels) {
        return CourierAssignment.create(UUID.randomUUID(), courier, AssignmentType.PICKUP, null, GAMPAHA, TODAY,
                "Call the senders first", UUID.randomUUID(), parcels, NOW);
    }

    private long version(UUID id) {
        return jdbc.queryForObject("select version from dispatch.courier_assignments where id = ?", Long.class, id);
    }
}
