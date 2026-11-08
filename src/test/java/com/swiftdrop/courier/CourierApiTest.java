package com.swiftdrop.courier;

import com.swiftdrop.IntegrationTest;
import com.swiftdrop.delivery.ChangeStatusCommand;
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
import com.swiftdrop.dispatch.TaskProblem;
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.shared.reference.District;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for the courier API tests. Delivery and dispatch are Mockito mocks (nothing implements them yet), answered
 * from two in-memory maps that each test fills with {@link #given(DeliverySnapshot)} and {@link #given(AssignmentView)}.
 * The writes apply the change to the maps like the real modules would, so responses show the new state. These tests
 * check only the courier module: roles, ownership, check order, request rules, the parcel moves and the mapping. All
 * subclasses share one Spring context.
 */
abstract class CourierApiTest extends IntegrationTest {

    protected static final String BASE = "/api/v1/courier/assignments";
    protected static final String PARCELS = "/api/v1/courier/parcels";

    private static final AtomicInteger TRACKING_SEQUENCE = new AtomicInteger(1);

    @MockitoBean
    protected DeliveryApi delivery;

    @MockitoBean
    protected AssignmentApi assignments;

    @Autowired
    protected IdentityApi identity;

    private final Map<UUID, DeliverySnapshot> parcels = new ConcurrentHashMap<>();
    private final Map<UUID, AssignmentView> tasks = new ConcurrentHashMap<>();

    protected record Courier(UUID id, Cookie cookie) {
    }

    /** One parcel in a task built by {@link #task}, with its current outcome. */
    protected record Item(DeliverySnapshot parcel, ItemOutcome outcome) {
    }

    @BeforeEach
    void answerFromTheMaps() {
        when(delivery.findById(any())).thenAnswer(call -> Optional.ofNullable(parcels.get(call.<UUID>getArgument(0))));
        when(delivery.findAllById(anyCollection())).thenAnswer(call -> call.<Collection<UUID>>getArgument(0).stream()
                .map(parcels::get).filter(Objects::nonNull).toList());
        when(delivery.findByTrackingNumber(anyString())).thenAnswer(call -> parcels.values().stream()
                .filter(parcel -> parcel.trackingNumber().equals(call.getArgument(0))).findFirst());
        when(delivery.history(any())).thenReturn(List.of());
        when(delivery.changeStatus(any())).thenAnswer(call -> changeStatus(call.getArgument(0)));

        when(assignments.findByIdForCourier(any(), any())).thenAnswer(call ->
                Optional.ofNullable(tasks.get(call.<UUID>getArgument(0)))
                        .filter(task -> task.courierId().equals(call.getArgument(1))));
        when(assignments.recordOutcome(any())).thenAnswer(call -> recordOutcome(call.getArgument(0)));
        when(assignments.complete(any(), any())).thenAnswer(call -> complete(call.getArgument(0)));
    }

    /** An ACTIVE courier (created through IdentityApi, as an admin would) and its login cookie. */
    protected Courier registerCourier() throws Exception {
        String email = uniqueEmail();
        UserSummary user = identity.registerUser("Kamal Jayawardena", email, stored(uniquePhone()), PASSWORD,
                Role.COURIER, UserStatus.ACTIVE);
        Cookie cookie = tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
        return new Courier(user.id(), cookie);
    }

    // ---- Parcels ----

    /** A parcel from Delgoda to Deniyaya. {@code branch} is used only for AT_BRANCH. The only DeliverySnapshot builder. */
    protected static DeliverySnapshot parcel(ParcelStatus status, District branch) {
        Instant now = Instant.now();
        return new DeliverySnapshot(UUID.randomUUID(),
                String.format("SD2611%06d", TRACKING_SEQUENCE.getAndIncrement()), status, null, UUID.randomUUID(),
                new DeliveryContact("Delgoda Spice Traders", "+94760000101", "14 Kandy Road", "Delgoda",
                        District.GAMPAHA),
                new DeliveryContact("Nimal Perera", "+94760000201", "45 Rakwana Road", "Deniyaya", District.MATARA),
                new BigDecimal("1.20"), new BigDecimal("2450.00"), "Call before you arrive.", null, null,
                now.minus(5, ChronoUnit.HOURS), now.minus(1, ChronoUnit.HOURS),
                status == ParcelStatus.DELIVERED ? "Nimal Perera" : null, false,
                status == ParcelStatus.FAILED ? FailureReason.RECIPIENT_UNAVAILABLE : null,
                "ORDER-1", new BigDecimal("450.00"), "LKR", 3,
                status == ParcelStatus.AT_BRANCH ? branch : null);
    }

    protected static DeliverySnapshot parcel(ParcelStatus status) {
        return parcel(status, null);
    }

    /** Makes delivery know this parcel. */
    protected DeliverySnapshot given(DeliverySnapshot parcel) {
        parcels.put(parcel.id(), parcel);
        return parcel;
    }

    protected DeliverySnapshot current(DeliverySnapshot parcel) {
        return parcels.get(parcel.id());
    }

    // ---- Tasks ----

    /**
     * A task of this type: PICKUP to Gampaha, TRANSFER Gampaha → Matara, DELIVERY from Matara. Items keep the given
     * order as their sequence; a closing outcome is closed, NOT_* get a problem, FAILED and RETURNED a reason.
     */
    protected static AssignmentView task(UUID courierId, AssignmentType type, AssignmentStatus status,
                                         Instant assignedAt, Item... items) {
        UUID id = UUID.randomUUID();
        List<AssignmentItemView> views = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            ItemOutcome outcome = items[i].outcome();
            boolean notTaken = outcome == ItemOutcome.NOT_COLLECTED || outcome == ItemOutcome.NOT_LOADED;
            boolean failed = outcome == ItemOutcome.FAILED || outcome == ItemOutcome.RETURNED;
            views.add(new AssignmentItemView(id, items[i].parcel().id(), i + 1, outcome,
                    notTaken ? TaskProblem.SENDER_UNAVAILABLE : null,
                    failed ? FailureReason.RECIPIENT_UNAVAILABLE : null,
                    null, null, null, assignedAt, outcome.closes(type) ? assignedAt.plusSeconds(60) : null));
        }
        return new AssignmentView(id, courierId, type, status,
                type == AssignmentType.PICKUP ? null : type == AssignmentType.TRANSFER ? District.GAMPAHA
                        : District.MATARA,
                type == AssignmentType.DELIVERY ? null : type == AssignmentType.PICKUP ? District.GAMPAHA
                        : District.MATARA,
                LocalDate.now(), "Morning round", assignedAt,
                status == AssignmentStatus.ASSIGNED ? null : assignedAt.plusSeconds(30),
                status == AssignmentStatus.COMPLETED ? assignedAt.plusSeconds(3600) : null,
                status == AssignmentStatus.CANCELLED ? assignedAt.plusSeconds(3600) : null,
                views, 0);
    }

    protected static AssignmentView task(UUID courierId, AssignmentType type, AssignmentStatus status,
                                         Item... items) {
        return task(courierId, type, status, Instant.now().minus(2, ChronoUnit.HOURS), items);
    }

    protected static Item item(DeliverySnapshot parcel, ItemOutcome outcome) {
        return new Item(parcel, outcome);
    }

    /** Makes dispatch know this task, and delivery its parcels. */
    protected AssignmentView given(AssignmentView task, DeliverySnapshot... parcelsOfTask) {
        tasks.put(task.id(), task);
        for (DeliverySnapshot parcel : parcelsOfTask) {
            given(parcel);
        }
        return task;
    }

    protected AssignmentView current(AssignmentView task) {
        return tasks.get(task.id());
    }

    // ---- Requests ----

    protected ResultActions postOutcome(AssignmentView task, DeliverySnapshot parcel, Map<String, ?> body,
                                        Cookie cookie) throws Exception {
        return postOutcome(task.id(), parcel.id(), json.writeValueAsString(body), cookie);
    }

    protected ResultActions postOutcome(UUID taskId, UUID parcelId, String body, Cookie cookie) throws Exception {
        return mvc.perform(post(BASE + "/{id}/items/{parcelId}/outcome", taskId, parcelId)
                .contentType(MediaType.APPLICATION_JSON).content(body).cookie(cookie));
    }

    protected ResultActions postHandover(AssignmentView task, Map<String, ?> body, Cookie cookie) throws Exception {
        return mvc.perform(post(BASE + "/{id}/handover", task.id())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)).cookie(cookie));
    }

    protected ResultActions postComplete(AssignmentView task, Cookie cookie) throws Exception {
        return mvc.perform(post(BASE + "/{id}/complete", task.id()).cookie(cookie));
    }

    // ---- What was sent ----

    protected List<ChangeStatusCommand> sentStatusChanges() {
        ArgumentCaptor<ChangeStatusCommand> captor = ArgumentCaptor.forClass(ChangeStatusCommand.class);
        verify(delivery, atLeast(0)).changeStatus(captor.capture());
        return captor.getAllValues();
    }

    protected List<RecordOutcomeCommand> sentOutcomes() {
        ArgumentCaptor<RecordOutcomeCommand> captor = ArgumentCaptor.forClass(RecordOutcomeCommand.class);
        verify(assignments, atLeast(0)).recordOutcome(captor.capture());
        return captor.getAllValues();
    }

    // ---- The mocks' writes ----

    private DeliverySnapshot changeStatus(ChangeStatusCommand command) {
        DeliverySnapshot parcel = parcels.get(command.parcelId());
        DeliverySnapshot updated = new DeliverySnapshot(parcel.id(), parcel.trackingNumber(), command.toStatus(),
                parcel.customerId(), parcel.merchantId(), parcel.pickup(), parcel.recipient(), parcel.weightKg(),
                parcel.codAmount(), parcel.instructions(), null, null, parcel.createdAt(), Instant.now(),
                command.recipientName(), false, command.failureReason(), parcel.merchantOrderRef(),
                parcel.feeAmount(), parcel.currency(), parcel.version() + 1, command.branch());
        parcels.put(updated.id(), updated);
        return updated;
    }

    private AssignmentView recordOutcome(RecordOutcomeCommand command) {
        AssignmentView task = tasks.get(command.assignmentId());
        Instant now = Instant.now();
        List<AssignmentItemView> items = task.items().stream()
                .map(item -> !item.parcelId().equals(command.parcelId()) ? item
                        : new AssignmentItemView(item.assignmentId(), item.parcelId(), item.sequence(),
                                command.outcome(), command.problem(),
                                command.failureReason() != null ? command.failureReason() : item.failureReason(),
                                command.note(), command.receivedBy(), command.recipientName(), now,
                                command.outcome().closes(task.type()) ? now : null))
                .toList();
        AssignmentView updated = new AssignmentView(task.id(), task.courierId(), task.type(),
                AssignmentStatus.IN_PROGRESS, task.originBranch(), task.destinationBranch(), task.scheduledFor(),
                task.note(), task.assignedAt(), task.startedAt() != null ? task.startedAt() : now, null, null, items,
                task.version() + 1);
        tasks.put(updated.id(), updated);
        return updated;
    }

    private AssignmentView complete(UUID taskId) {
        AssignmentView task = tasks.get(taskId);
        AssignmentView updated = new AssignmentView(task.id(), task.courierId(), task.type(),
                AssignmentStatus.COMPLETED, task.originBranch(), task.destinationBranch(), task.scheduledFor(),
                task.note(), task.assignedAt(), task.startedAt(), Instant.now(), null, task.items(),
                task.version() + 1);
        tasks.put(updated.id(), updated);
        return updated;
    }
}
