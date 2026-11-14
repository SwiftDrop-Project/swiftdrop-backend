package com.swiftdrop.courier.internal.controller;

import com.swiftdrop.courier.internal.application.CourierAssignmentService;
import com.swiftdrop.courier.internal.application.CourierViews.CourierToday;
import com.swiftdrop.courier.internal.dto.AssignmentDetailResponse;
import com.swiftdrop.courier.internal.dto.AssignmentSummaryResponse;
import com.swiftdrop.courier.internal.dto.CourierTodayResponse;
import com.swiftdrop.courier.internal.dto.HandoverRequest;
import com.swiftdrop.courier.internal.dto.ItemOutcomeRequest;
import com.swiftdrop.shared.error.BusinessRuleException;
import com.swiftdrop.shared.error.ErrorCode;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.function.Supplier;

/** The logged-in courier's tasks (assignments). COURIER only; other couriers' tasks and parcels answer 404. */
@RestController
@RequestMapping("/courier/assignments")
@PreAuthorize("hasRole('COURIER')")
@Tag(name = "Courier - tasks", description = "Today's tasks, parcel outcomes, branch handovers and task history "
        + "(COURIER only)")
public class CourierAssignmentController {

    private final CourierAssignmentService courierAssignments;

    public CourierAssignmentController(CourierAssignmentService courierAssignments) {
        this.courierAssignments = courierAssignments;
    }

    @GetMapping("/today")
    @Operation(summary = "My open tasks, plus the ones completed or cancelled today (Sri Lankan time)")
    public CourierTodayResponse today(@AuthenticationPrincipal Jwt jwt) {
        CourierToday today = courierAssignments.today(CurrentUser.id(jwt));
        return new CourierTodayResponse(today.date(), today.open(), today.completedToday(),
                today.assignments().stream().map(AssignmentSummaryResponse::from).toList());
    }

    @GetMapping("/history")
    @Operation(summary = "My completed and cancelled tasks, newest first; optionally only those with one parcel")
    public PageResponse<AssignmentSummaryResponse> history(@AuthenticationPrincipal Jwt jwt,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size,
                                                           @RequestParam(required = false) String trackingNumber) {
        return PageResponse.from(courierAssignments.history(CurrentUser.id(jwt), page, size, trackingNumber),
                AssignmentSummaryResponse::from);
    }

    @GetMapping("/{assignmentId}")
    @Operation(summary = "One of my tasks with its parcels")
    public AssignmentDetailResponse detail(@PathVariable UUID assignmentId, @AuthenticationPrincipal Jwt jwt) {
        return AssignmentDetailResponse.from(courierAssignments.detail(CurrentUser.id(jwt), assignmentId));
    }

    @PostMapping("/{assignmentId}/items/{parcelId}/outcome")
    @Operation(summary = "Record what happened to one parcel in my task (collected, loaded, delivered, failed, "
            + "couldn't take it)")
    public AssignmentDetailResponse recordOutcome(@PathVariable UUID assignmentId, @PathVariable UUID parcelId,
                                                  @Valid @RequestBody ItemOutcomeRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        return AssignmentDetailResponse.from(inOneStep(() ->
                courierAssignments.recordOutcome(CurrentUser.id(jwt), assignmentId, parcelId, request)));
    }

    @PostMapping("/{assignmentId}/handover")
    @Operation(summary = "Hand parcels of my task over at the branch (all or nothing)")
    public AssignmentDetailResponse handover(@PathVariable UUID assignmentId,
                                             @Valid @RequestBody HandoverRequest request,
                                             @AuthenticationPrincipal Jwt jwt) {
        return AssignmentDetailResponse.from(inOneStep(() ->
                courierAssignments.handover(CurrentUser.id(jwt), assignmentId, request)));
    }

    @PostMapping("/{assignmentId}/complete")
    @Operation(summary = "Complete my task once every parcel in it is closed")
    public AssignmentSummaryResponse complete(@PathVariable UUID assignmentId, @AuthenticationPrincipal Jwt jwt) {
        return AssignmentSummaryResponse.from(inOneStep(() ->
                courierAssignments.complete(CurrentUser.id(jwt), assignmentId)));
    }

    /**
     * Runs one write. Someone else changed the task or parcel at the same time (optimistic lock, possibly only at
     * commit, after the service returned): 409 TASK_STEP_NOT_ALLOWED, so the courier refreshes, instead of a 500.
     */
    private static <T> T inOneStep(Supplier<T> write) {
        try {
            return write.get();
        } catch (OptimisticLockingFailureException ex) {
            throw new BusinessRuleException(ErrorCode.TASK_STEP_NOT_ALLOWED);
        }
    }
}
