package com.swiftdrop.courier;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.DeliveryApi;
import com.swiftdrop.delivery.DeliveryContact;
import com.swiftdrop.delivery.DeliverySnapshot;
import com.swiftdrop.delivery.FailureReason;
import com.swiftdrop.delivery.ParcelStatus;
import com.swiftdrop.dispatch.AssignmentApi;
import com.swiftdrop.dispatch.AssignmentItemView;
import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.AssignmentView;
import com.swiftdrop.dispatch.ItemOutcome;
import com.swiftdrop.dispatch.RecordOutcomeCommand;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.shared.reference.District;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for the courier API tests: delivery and dispatch are Mockito mocks, so these tests check only the courier
 * module: roles, ownership, request rules and mapping. Dispatch is a small in-memory stand-in: {@link #given} puts the
 * parcel in a one-parcel task of its courier, at the step its status implies. The real modules together are tested in
 * {@code CourierDispatchFlowTest}. All subclasses share one Spring context.
 */
abstract class CourierApiTest extends IntegrationTest {

    protected static final String BASE = "/api/v1/courier/deliveries";

    private static final AtomicInteger TRACKING_SEQUENCE = new AtomicInteger(1);

    @MockitoBean
    protected DeliveryApi delivery;

    @MockitoBean
    protected AssignmentApi assignments;

    @Autowired
    protected IdentityApi identity;

    /** The stand-in's tasks by id, and the parcels {@link #given} registered. */
    private final Map<UUID, AssignmentView> tasks = new ConcurrentHashMap<>();
    private final Map<UUID, DeliverySnapshot> parcels = new ConcurrentHashMap<>();

    protected record Courier(UUID id, Cookie cookie) {
    }

    @BeforeEach
    void fakeDispatch() {
        tasks.clear();
        parcels.clear();
        when(assignments.findOpenForCourier(any(), any())).thenAnswer(call ->
                tasks.values().stream().filter(task -> task.courierId().equals(call.getArgument(0))).toList());
        when(assignments.findItemsForCourierAndParcel(any(), any())).thenAnswer(call ->
                tasks.values().stream().filter(task -> task.courierId().equals(call.getArgument(0)))
                        .flatMap(task -> task.items().stream())
                        .filter(item -> item.parcelId().equals(call.getArgument(1))).toList());
        when(assignments.findByIdForCourier(any(), any())).thenAnswer(call ->
                Optional.ofNullable(tasks.get((UUID) call.getArgument(0)))
                        .filter(task -> task.courierId().equals(call.getArgument(1))));
        when(assignments.recordOutcome(any())).thenAnswer(call -> record(call.getArgument(0)));
        when(assignments.complete(any(), any())).thenAnswer(call -> tasks.computeIfPresent(call.getArgument(0),
                (id, task) -> withItems(task, AssignmentStatus.COMPLETED, task.items())));
        when(delivery.findAllById(any())).thenAnswer(call -> ((Collection<?>) call.getArgument(0)).stream()
                .map(parcels::get).filter(Objects::nonNull).toList());
    }

    /** An ACTIVE courier (created through IdentityApi, as an admin would) and its login cookie. */
    protected Courier registerCourier() throws Exception {
        String email = uniqueEmail();
        UserSummary user = identity.registerUser("Kamal Jayawardena", email, stored(uniquePhone()), PASSWORD,
                Role.COURIER, UserStatus.ACTIVE);
        Cookie cookie = tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
        return new Courier(user.id(), cookie);
    }

    /** A parcel for {@code courierId}. The only place that builds a DeliverySnapshot. */
    protected static DeliverySnapshot parcel(UUID courierId, ParcelStatus status, Instant assignedAt,
                                             Instant updatedAt) {
        return new DeliverySnapshot(UUID.randomUUID(),
                String.format("SD2611%06d", TRACKING_SEQUENCE.getAndIncrement()), status, null, UUID.randomUUID(),
                new DeliveryContact("Lanka Crafts", "+94760000101", "12 High Level Road", "Nugegoda", District.COLOMBO),
                new DeliveryContact("Nimal Perera", "+94760000201", "45 Peradeniya Road", "Kandy", District.KANDY),
                new BigDecimal("1.20"), new BigDecimal("2450.00"), "Call before you arrive.", courierId, assignedAt,
                assignedAt.minus(1, ChronoUnit.HOURS), updatedAt,
                status == ParcelStatus.DELIVERED ? "Nimal Perera" : null, false,
                status == ParcelStatus.FAILED ? FailureReason.RECIPIENT_UNAVAILABLE : null,
                "ORDER-1", new BigDecimal("450.00"), "LKR", 3,
                status == ParcelStatus.AT_BRANCH ? District.COLOMBO : null);
    }

    protected static DeliverySnapshot parcel(UUID courierId, ParcelStatus status) {
        Instant now = Instant.now();
        return parcel(courierId, status, now.minus(2, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS));
    }

    /**
     * Makes delivery return this parcel (with an empty history) and puts it in a task of the courier in
     * {@code assignedCourierId}: CREATED / PICKED_UP in a pickup to COLOMBO, IN_TRANSIT in a transfer from COLOMBO to
     * KANDY, AT_BRANCH and later in a delivery from COLOMBO, at the matching step. The task is assigned at the
     * parcel's {@code assignedAt}.
     */
    protected DeliverySnapshot given(DeliverySnapshot parcel) {
        when(delivery.findById(parcel.id())).thenReturn(Optional.of(parcel));
        when(delivery.history(parcel.id())).thenReturn(List.of());
        parcels.put(parcel.id(), parcel);
        AssignmentType type = switch (parcel.status()) {
            case CREATED, PICKED_UP -> AssignmentType.PICKUP;
            case IN_TRANSIT -> AssignmentType.TRANSFER;
            default -> AssignmentType.DELIVERY;
        };
        ItemOutcome outcome = switch (parcel.status()) {
            case CREATED, AT_BRANCH -> ItemOutcome.PENDING;
            case PICKED_UP -> ItemOutcome.COLLECTED;
            case IN_TRANSIT, OUT_FOR_DELIVERY -> ItemOutcome.LOADED;
            case DELIVERED -> ItemOutcome.DELIVERED;
            case FAILED -> ItemOutcome.FAILED;
        };
        UUID taskId = UUID.randomUUID();
        AssignmentItemView item = item(taskId, parcel.id(), type, outcome, parcel.updatedAt());
        boolean closed = item.isClosed();
        District origin = type == AssignmentType.PICKUP ? null : District.COLOMBO;
        District destination = switch (type) {
            case PICKUP -> District.COLOMBO;
            case TRANSFER -> District.KANDY;
            case DELIVERY -> null;
        };
        tasks.put(taskId, new AssignmentView(taskId, parcel.assignedCourierId(), type,
                closed ? AssignmentStatus.COMPLETED : AssignmentStatus.IN_PROGRESS, origin, destination, null, null,
                parcel.assignedAt(), parcel.assignedAt(), closed ? parcel.updatedAt() : null, null, List.of(item), 1));
        return parcel;
    }

    /** The stand-in's current task that holds this parcel. */
    protected AssignmentView taskOf(UUID parcelId) {
        return tasks.values().stream()
                .filter(task -> task.items().stream().anyMatch(item -> item.parcelId().equals(parcelId)))
                .findFirst().orElseThrow();
    }

    private static AssignmentItemView item(UUID taskId, UUID parcelId, AssignmentType type, ItemOutcome outcome,
                                           Instant at) {
        return new AssignmentItemView(taskId, parcelId, 1, outcome, null, null, null, null, null, at,
                outcome.closes(type) ? at : null);
    }

    private AssignmentView record(RecordOutcomeCommand command) {
        return tasks.computeIfPresent(command.assignmentId(), (id, task) -> withItems(task,
                AssignmentStatus.IN_PROGRESS, task.items().stream()
                        .map(item -> item.parcelId().equals(command.parcelId())
                                ? item(id, item.parcelId(), task.type(), command.outcome(), Instant.now())
                                : item)
                        .toList()));
    }

    private static AssignmentView withItems(AssignmentView task, AssignmentStatus status,
                                            List<AssignmentItemView> items) {
        return new AssignmentView(task.id(), task.courierId(), task.type(), status, task.originBranch(),
                task.destinationBranch(), task.scheduledFor(), task.note(), task.assignedAt(), task.startedAt(),
                status == AssignmentStatus.COMPLETED ? Instant.now() : null, null, items, task.version() + 1);
    }

    protected ResultActions postStatus(UUID parcelId, Map<String, ?> body, Cookie cookie) throws Exception {
        return mvc.perform(post(BASE + "/{id}/status", parcelId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .cookie(cookie));
    }

    protected ResultActions postStatusRaw(UUID parcelId, String body, Cookie cookie) throws Exception {
        return mvc.perform(post(BASE + "/{id}/status", parcelId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .cookie(cookie));
    }
}
