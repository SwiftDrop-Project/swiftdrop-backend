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
import com.swiftdrop.identity.IdentityApi;
import com.swiftdrop.identity.Role;
import com.swiftdrop.identity.UserStatus;
import com.swiftdrop.identity.UserSummary;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.error.NotFoundException;
import com.swiftdrop.shared.reference.District;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base for the courier API tests: delivery and dispatch are Mockito mocks, so these tests check only the courier
 * module: roles, ownership, request rules and mapping. Dispatch is a small in-memory stand-in that keeps the
 * {@code AssignmentApi} contract (404 for another courier's task, ASSIGNMENT_CLOSED, TASK_STEP_NOT_ALLOWED from
 * {@code ItemOutcome.canMoveTo}, ASSIGNMENT_NOT_FINISHED, closedAt from {@code ItemOutcome.closes}): {@link #given} puts
 * the parcel in a one-parcel task of its courier, at the step its status implies; {@link #givenTask} groups several
 * parcels in one task, as a dispatcher may. The real modules together are tested in {@code CourierDispatchFlowTest} and
 * the {@code RealWorkflowTest} subclasses. All subclasses share one Spring context.
 */
abstract class CourierApiTest extends IntegrationTest {

    protected static final String BASE = "/api/v1/courier/deliveries";

    /** A proof photo id for DELIVERED requests: delivery is a mock here, so any stored photo id will do. */
    protected static final String PROOF_ID = "0b6c7c1e-5d0f-4a51-9d2e-3a4f5b6c7d8e";

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
        when(assignments.complete(any(), any())).thenAnswer(call -> complete(call.getArgument(0),
                call.getArgument(1)));
        when(delivery.findAllById(any())).thenAnswer(call -> ((Collection<?>) call.getArgument(0)).stream()
                .map(parcels::get).filter(Objects::nonNull).toList());
        // Delivery answers with the parcels as the test registered (or changed) them; history is empty unless a test
        // stubs it for one parcel.
        when(delivery.findById(any())).thenAnswer(call -> Optional.ofNullable(parcels.get((UUID) call.getArgument(0))));
        when(delivery.history(any())).thenReturn(List.of());
    }

    /**
     * Makes delivery apply every status change as the real one would: the parcel gets the new status, the branch for
     * AT_BRANCH, the proof name for DELIVERED and the reason for FAILED, and later reads see it.
     */
    protected void deliveryAppliesChanges() {
        when(delivery.changeStatus(any())).thenAnswer(call -> {
            ChangeStatusCommand command = call.getArgument(0);
            DeliverySnapshot moved = moved(parcels.get(command.parcelId()), command);
            parcels.put(moved.id(), moved);
            return moved;
        });
    }

    /** The parcel as delivery has it now. */
    protected DeliverySnapshot current(UUID parcelId) {
        return parcels.get(parcelId);
    }

    @SuppressWarnings("deprecation")
    private static DeliverySnapshot moved(DeliverySnapshot p, ChangeStatusCommand command) {
        ParcelStatus status = command.toStatus();
        return new DeliverySnapshot(p.id(), p.trackingNumber(), status, p.customerId(), p.merchantId(), p.pickup(),
                p.recipient(), p.weightKg(), p.codAmount(), p.instructions(), p.assignedCourierId(), p.assignedAt(),
                p.createdAt(), Instant.now(),
                status == ParcelStatus.DELIVERED ? command.recipientName() : null,
                status == ParcelStatus.DELIVERED && command.proofPhotoId() != null,
                status == ParcelStatus.FAILED ? command.failureReason() : null,
                p.merchantOrderRef(), p.feeAmount(), p.currency(), p.version() + 1,
                status == ParcelStatus.AT_BRANCH ? command.branch() : null,
                p.size(), p.vehicle(), p.billableWeightKg(), p.rateCardVersion());
    }

    /** An ACTIVE courier (created through IdentityApi, as an admin would) and its login cookie. */
    protected Courier registerCourier() throws Exception {
        String email = uniqueEmail();
        UserSummary user = identity.registerUser("Kamal Jayawardena", email, stored(uniquePhone()), PASSWORD,
                Role.COURIER, UserStatus.ACTIVE);
        Cookie cookie = tokenCookie(login(email, PASSWORD, false).andExpect(status().isOk()).andReturn());
        return new Courier(user.id(), cookie);
    }

    /** A parcel for {@code courierId}; an AT_BRANCH parcel is at the COLOMBO branch. */
    protected static DeliverySnapshot parcel(UUID courierId, ParcelStatus status, Instant assignedAt,
                                             Instant updatedAt) {
        return parcel(courierId, status, assignedAt, updatedAt,
                status == ParcelStatus.AT_BRANCH ? District.COLOMBO : null);
    }

    /** A parcel for {@code courierId} at {@code branch}. The only place that builds a DeliverySnapshot. */
    protected static DeliverySnapshot parcel(UUID courierId, ParcelStatus status, Instant assignedAt,
                                             Instant updatedAt, District branch) {
        return new DeliverySnapshot(UUID.randomUUID(),
                String.format("SD2611%06d", TRACKING_SEQUENCE.getAndIncrement()), status, null, UUID.randomUUID(),
                new DeliveryContact("Lanka Crafts", "+94760000101", "12 High Level Road", "Nugegoda", District.COLOMBO),
                new DeliveryContact("Nimal Perera", "+94760000201", "45 Peradeniya Road", "Kandy", District.KANDY),
                new BigDecimal("1.20"), new BigDecimal("2450.00"), "Call before you arrive.", courierId, assignedAt,
                assignedAt.minus(1, ChronoUnit.HOURS), updatedAt,
                status == ParcelStatus.DELIVERED ? "Nimal Perera" : null, false,
                status == ParcelStatus.FAILED ? FailureReason.RECIPIENT_UNAVAILABLE : null,
                "ORDER-1", new BigDecimal("450.00"), "LKR", 3, branch);
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

    /** One parcel of a {@link #givenTask} and the outcome it has reached in that task. */
    protected record Step(DeliverySnapshot parcel, ItemOutcome outcome) {
    }

    protected static Step step(DeliverySnapshot parcel, ItemOutcome outcome) {
        return new Step(parcel, outcome);
    }

    /**
     * A task of {@code courierId} grouping several parcels, as a dispatcher creates it: a pickup to COLOMBO, a
     * delivery or transfer from COLOMBO (a transfer goes to KANDY). ASSIGNED while every parcel is PENDING, else
     * IN_PROGRESS. Delivery returns each parcel with an empty history.
     */
    protected AssignmentView givenTask(UUID courierId, AssignmentType type, Step... steps) {
        UUID taskId = UUID.randomUUID();
        Instant now = Instant.now();
        List<AssignmentItemView> items = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) {
            DeliverySnapshot parcel = steps[i].parcel();
            parcels.put(parcel.id(), parcel);
            ItemOutcome outcome = steps[i].outcome();
            Instant at = now.minus(steps.length - i, ChronoUnit.MINUTES);
            items.add(new AssignmentItemView(taskId, parcel.id(), i + 1, outcome, null, null, null, null, null, at,
                    outcome.closes(type) ? at : null));
        }
        boolean started = items.stream().anyMatch(item -> item.outcome() != ItemOutcome.PENDING);
        AssignmentView task = new AssignmentView(taskId, courierId, type,
                started ? AssignmentStatus.IN_PROGRESS : AssignmentStatus.ASSIGNED,
                type == AssignmentType.PICKUP ? null : District.COLOMBO,
                switch (type) {
                    case PICKUP -> District.COLOMBO;
                    case TRANSFER -> District.KANDY;
                    case DELIVERY -> null;
                },
                null, null, now.minus(1, ChronoUnit.HOURS), started ? now.minus(1, ChronoUnit.HOURS) : null,
                null, null, items, 1);
        tasks.put(taskId, task);
        return task;
    }

    /** Puts this task in the stand-in as it is (e.g. CANCELLED). */
    protected void replaceTask(AssignmentView task) {
        tasks.put(task.id(), task);
    }

    /** The stand-in's current task that holds this parcel. */
    protected AssignmentView taskOf(UUID parcelId) {
        return tasks.values().stream()
                .filter(task -> task.items().stream().anyMatch(item -> item.parcelId().equals(parcelId)))
                .findFirst().orElseThrow();
    }

    /** The stand-in's current task. */
    protected AssignmentView task(UUID taskId) {
        return tasks.get(taskId);
    }

    private static AssignmentItemView item(UUID taskId, UUID parcelId, AssignmentType type, ItemOutcome outcome,
                                           Instant at) {
        return new AssignmentItemView(taskId, parcelId, 1, outcome, null, null, null, null, null, at,
                outcome.closes(type) ? at : null);
    }

    /** As {@code AssignmentApi.recordOutcome} promises: 404, ASSIGNMENT_CLOSED, TASK_STEP_NOT_ALLOWED, closedAt. */
    private AssignmentView record(RecordOutcomeCommand command) {
        AssignmentView task = tasks.get(command.assignmentId());
        if (task == null || !task.courierId().equals(command.courierId())) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        if (task.status().isClosed()) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_CLOSED);
        }
        AssignmentItemView current = task.items().stream()
                .filter(item -> item.parcelId().equals(command.parcelId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND));
        if (!current.outcome().canMoveTo(task.type(), command.outcome())) {
            throw new BusinessRuleException(ErrorCode.TASK_STEP_NOT_ALLOWED);
        }
        Instant now = Instant.now();
        AssignmentItemView changed = new AssignmentItemView(task.id(), current.parcelId(), current.sequence(),
                command.outcome(), command.problem(), command.failureReason(), command.note(), command.receivedBy(),
                command.recipientName(), now, command.outcome().closes(task.type()) ? now : null);
        AssignmentView updated = withItems(task, AssignmentStatus.IN_PROGRESS, task.items().stream()
                .map(item -> item.parcelId().equals(command.parcelId()) ? changed : item)
                .toList());
        tasks.put(task.id(), updated);
        return updated;
    }

    /** As {@code AssignmentApi.complete} promises: ASSIGNMENT_CLOSED, ASSIGNMENT_NOT_FINISHED. */
    private AssignmentView complete(UUID taskId, UUID courierId) {
        AssignmentView task = tasks.get(taskId);
        if (task == null || !task.courierId().equals(courierId)) {
            throw new NotFoundException(ErrorCode.NOT_FOUND);
        }
        if (task.status().isClosed()) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_CLOSED);
        }
        if (task.items().stream().anyMatch(item -> !item.isClosed())) {
            throw new BusinessRuleException(ErrorCode.ASSIGNMENT_NOT_FINISHED);
        }
        AssignmentView completed = withItems(task, AssignmentStatus.COMPLETED, task.items());
        tasks.put(taskId, completed);
        return completed;
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

    protected ResultActions postUnableToCollect(UUID parcelId, Map<String, ?> body, Cookie cookie) throws Exception {
        return mvc.perform(post(BASE + "/{id}/unable-to-collect", parcelId)
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

    /** POST .../{parcelId}/proof with {@code content} as the multipart field "file". */
    protected ResultActions postProof(UUID parcelId, byte[] content, Cookie cookie) throws Exception {
        return mvc.perform(multipart(BASE + "/{id}/proof", parcelId)
                .file(new MockMultipartFile("file", "slip.jpg", "image/jpeg", content))
                .cookie(cookie));
    }

    /** A small, real JPEG (a photo of a slip, as far as the checks can tell). */
    protected static byte[] jpeg() {
        return image("jpg");
    }

    protected static byte[] image(String format) {
        try {
            BufferedImage picture = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(picture, format, out)) {
                throw new IllegalStateException("No writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
