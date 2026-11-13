package com.swiftdrop.dispatch.internal.controller;

import com.swiftdrop.dispatch.AssignmentStatus;
import com.swiftdrop.dispatch.AssignmentType;
import com.swiftdrop.dispatch.internal.application.DispatchQueryService;
import com.swiftdrop.dispatch.internal.application.DispatchService;
import com.swiftdrop.dispatch.internal.dto.AddParcelsRequest;
import com.swiftdrop.dispatch.internal.dto.AssignCourierRequest;
import com.swiftdrop.dispatch.internal.dto.AssignmentDetailResponse;
import com.swiftdrop.dispatch.internal.dto.AssignmentSummaryResponse;
import com.swiftdrop.dispatch.internal.dto.CourierWorkloadResponse;
import com.swiftdrop.dispatch.internal.dto.CreateAssignmentRequest;
import com.swiftdrop.dispatch.internal.dto.DeliveryIssueResponse;
import com.swiftdrop.dispatch.internal.dto.DispatchOverviewResponse;
import com.swiftdrop.dispatch.internal.dto.ParcelAssignmentResponse;
import com.swiftdrop.dispatch.internal.dto.QueueKind;
import com.swiftdrop.dispatch.internal.dto.QueueParcelResponse;
import com.swiftdrop.dispatch.internal.dto.ReassignRequest;
import com.swiftdrop.identity.CourierSummary;
import com.swiftdrop.shared.error.FieldValidationException;
import com.swiftdrop.shared.reference.District;
import com.swiftdrop.shared.web.CurrentUser;
import com.swiftdrop.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Dispatch operations for dispatchers and admins: parcel queues, courier tasks, workloads and issues. */
@RestController
@RequestMapping("/dispatch")
@PreAuthorize("hasAnyRole('DISPATCHER', 'ADMIN')")
@Tag(name = "Dispatch", description = "Parcel queues, courier tasks and workloads (DISPATCHER and ADMIN)")
public class DispatchController {

    private final DispatchService dispatchService;
    private final DispatchQueryService queries;

    public DispatchController(DispatchService dispatchService, DispatchQueryService queries) {
        this.dispatchService = dispatchService;
        this.queries = queries;
    }

    // ---- Couriers ----

    @GetMapping("/couriers")
    @Operation(summary = "List active couriers eligible for parcel assignment (optionally filtered by branch)")
    public List<CourierSummary> listActiveCouriers(@RequestParam(required = false) District branch) {
        return dispatchService.listActiveCouriers(branch);
    }

    @GetMapping("/couriers/workload")
    @Operation(summary = "Active couriers with their open tasks, open parcels and tasks completed today")
    public List<CourierWorkloadResponse> workload(@RequestParam(required = false) District branch) {
        return queries.workload(branch);
    }

    // ---- Queues, overview, issues ----

    @GetMapping("/queue")
    @Operation(summary = "Parcels waiting for a task at a branch: new pickups or parcels at the branch")
    public List<QueueParcelResponse> queue(@RequestParam District branch,
                                           @RequestParam(defaultValue = "PICKUP") QueueKind kind) {
        return queries.queue(branch, kind);
    }

    @GetMapping("/overview")
    @Operation(summary = "Today's dispatch figures for a branch")
    public DispatchOverviewResponse overview(@RequestParam District branch) {
        return queries.overview(branch);
    }

    @GetMapping("/issues")
    @Operation(summary = "Parcels couriers couldn't collect, load or deliver at a branch (last 1-30 days)")
    public List<DeliveryIssueResponse> issues(@RequestParam District branch,
                                              @RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 30) {
            throw new FieldValidationException("days", "Choose between 1 and 30 days.");
        }
        return queries.issues(branch, days);
    }

    // ---- One-step assignment ----

    @PostMapping(path = "/parcels/{parcelId}/assign", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Assign a courier to a parcel's next step (creates a one-parcel task)")
    public ParcelAssignmentResponse assignCourier(@PathVariable UUID parcelId,
                                                  @AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody AssignCourierRequest request) {
        return dispatchService.assignCourier(parcelId, request.courierId(), CurrentUser.id(jwt));
    }

    // ---- Courier tasks ----

    @PostMapping(path = "/assignments", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a courier task (pickup, transfer or delivery) with 1-50 parcels")
    public AssignmentDetailResponse create(@AuthenticationPrincipal Jwt jwt,
                                           @Valid @RequestBody CreateAssignmentRequest request) {
        return dispatchService.create(request, CurrentUser.id(jwt));
    }

    @GetMapping("/assignments")
    @Operation(summary = "List courier tasks, newest first")
    public PageResponse<AssignmentSummaryResponse> list(
            @RequestParam(required = false) AssignmentStatus status,
            @RequestParam(required = false) AssignmentType type,
            @RequestParam(required = false) UUID courierId,
            @RequestParam(required = false) District branch,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) {
            throw new FieldValidationException("page", "The page number can't be negative.");
        }
        if (size < 1 || size > 100) {
            throw new FieldValidationException("size", "Choose a page size between 1 and 100.");
        }
        return queries.list(status, type, courierId, branch, date, page, size);
    }

    @GetMapping("/assignments/{id}")
    @Operation(summary = "One courier task with its parcels")
    public AssignmentDetailResponse detail(@PathVariable UUID id) {
        return queries.detail(id);
    }

    @PostMapping(path = "/assignments/{id}/parcels", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Add parcels to an open task")
    public AssignmentDetailResponse addParcels(@PathVariable UUID id, @Valid @RequestBody AddParcelsRequest request) {
        return dispatchService.addParcels(id, request);
    }

    @DeleteMapping("/assignments/{id}/parcels/{parcelId}")
    @Operation(summary = "Take a parcel the courier hasn't updated out of an open task")
    public AssignmentDetailResponse removeParcel(@PathVariable UUID id, @PathVariable UUID parcelId) {
        return dispatchService.removeParcel(id, parcelId);
    }

    @PostMapping(path = "/assignments/{id}/reassign", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Give a task the courier hasn't started to another courier")
    public AssignmentDetailResponse reassign(@PathVariable UUID id, @Valid @RequestBody ReassignRequest request) {
        return dispatchService.reassign(id, request.courierId());
    }

    @PostMapping("/assignments/{id}/cancel")
    @Operation(summary = "Cancel a task the courier hasn't started; its parcels go back to the queue")
    public AssignmentDetailResponse cancel(@PathVariable UUID id) {
        return dispatchService.cancel(id);
    }
}
